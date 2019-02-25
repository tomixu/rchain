package coop.rchain.rspace

import java.nio.ByteBuffer

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import scala.util.Random
import cats.effect.{Concurrent, ContextShift}
import cats.implicits._
import coop.rchain.shared.Log
import coop.rchain.catscontrib._
import coop.rchain.rspace.history.Branch
import coop.rchain.rspace.internal._
import coop.rchain.rspace.trace._
import coop.rchain.shared.SyncVarOps._
import com.typesafe.scalalogging.Logger
import coop.rchain.metrics.Metrics
import org.lmdbjava.Txn
import scodec.Codec

class RSpace[F[_], C, P, E, A, R, K] private[rspace] (
    store: IStore[F, C, P, A, K],
    branch: Branch
)(
    implicit
    serializeC: Serialize[C],
    serializeP: Serialize[P],
    serializeA: Serialize[A],
    serializeK: Serialize[K],
    val concurrent: Concurrent[F],
    logF: Log[F],
    contextShift: ContextShift[F],
    scheduler: ExecutionContext,
    metricsF: Metrics[F]
) extends RSpaceOps[F, C, P, E, A, R, K](store, branch)
    with ISpace[F, C, P, E, A, R, K] {

  override protected[this] val logger: Logger = Logger[this.type]

  private[this] implicit val MetricsSource = RSpaceMetricsSource
  private[this] val consumeCommLabel       = MetricsSource + ".comm.consume"
  private[this] val produceCommLabel       = MetricsSource + ".comm.produce"

  /*
   * Here, we create a cache of the data at each channel as `channelToIndexedData`
   * which is used for finding matches.  When a speculative match is found, we can
   * remove the matching datum from the remaining data candidates in the cache.
   *
   * Put another way, this allows us to speculatively remove matching data without
   * affecting the actual store contents.
   */
  private[this] def fetchChannelToIndexData(channels: Seq[C]) =
    store.withTxnF(store.createTxnReadF()) { txn =>
      channels.map { c: C =>
        c -> Random.shuffle(
          store.getData(txn, Seq(c)).zipWithIndex
        )
      }.toMap
    }

  private[this] def storeWaitingContinuation(
      channels: Seq[C],
      patterns: Seq[P],
      continuation: K,
      persist: Boolean,
      consumeRef: Consume
  ): F[Either[E, MaybeDataCandidate]] =
    for {
      _ <- store
            .withTxnF(store.createTxnWriteF()) { txn =>
              store.putWaitingContinuation(
                txn,
                channels,
                WaitingContinuation(
                  patterns,
                  continuation,
                  persist,
                  consumeRef
                )
              )
              for (channel <- channels)
                store.addJoin(txn, channel, channels)
            }
      _ = logger.debug(s"""|consume: no data found,
                           |storing <(patterns, continuation): ($patterns, $continuation)>
                           |at <channels: $channels>""".stripMargin.replace('\n', ' '))
    } yield None.asRight[E]

  private[this] def storePersistentData(dataCandidates: Seq[DataCandidate[C, R]]) =
    dataCandidates.toList
      .sortBy(_.datumIndex)(Ordering[Int].reverse)
      .traverse {
        case DataCandidate(
            candidateChannel,
            Datum(_, persistData, _),
            dataIndex
            ) if !persistData =>
          store.withTxnF(store.createTxnWriteF()) { txn =>
            store.removeDatum(
              txn,
              Seq(candidateChannel),
              dataIndex
            )
          }
        case _ =>
          ().pure[F]
      }

  private[this] def createContinuationResult(
      channels: Seq[C],
      patterns: Seq[P],
      continuation: K,
      persist: Boolean,
      consumeRef: Consume,
      dataCandidates: Seq[DataCandidate[C, R]]
  ) = {
    val contSequenceNumber: Int =
      nextSequenceNumber(consumeRef, dataCandidates)
    Right(
      Some(
        (
          ContResult(
            continuation,
            persist,
            channels,
            patterns,
            contSequenceNumber
          ),
          dataCandidates
            .map(dc => Result(dc.datum.a, dc.datum.persist))
        )
      )
    )
  }

  type MaybeDataCandidate = Option[(ContResult[C, P, K], Seq[Result[R]])]

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements")) // TODO remove when Kamon replaced with Metrics API
  override def consume(
      channels: Seq[C],
      patterns: Seq[P],
      continuation: K,
      persist: Boolean,
      sequenceNumber: Int
  )(
      implicit m: Match[P, E, A, R]
  ): F[Either[E, Option[(ContResult[C, P, K], Seq[Result[R]])]]] = {
    def storeWC(consumeRef: Consume) =
      storeWaitingContinuation(channels, patterns, continuation, persist, consumeRef)
    def wrapResult(consumeRef: Consume, dataCandidates: Seq[DataCandidate[C, R]]) =
      createContinuationResult(
        channels,
        patterns,
        continuation,
        persist,
        consumeRef,
        dataCandidates
      )

    contextShift.evalOn(scheduler) {
      if (channels.isEmpty) {
        val msg = "channels can't be empty"
        logF.error(msg) *> syncF.raiseError(new IllegalArgumentException(msg))
      } else if (channels.length =!= patterns.length) {
        val msg = "channels.length must equal patterns.length"
        logF.error(msg) *> syncF.raiseError(new IllegalArgumentException(msg))
      } else
        for {
          consumeRef <- Consume
                         .create(channels, patterns, continuation, persist, sequenceNumber)
                         .pure[F]
          result <- consumeLockF(channels) {
                     for {
                       _ <- logger
                             .debug(
                               s"""|consume: searching for data matching <patterns: $patterns>
                             |at <channels: $channels>""".stripMargin.replace('\n', ' ')
                             )
                             .pure[F]
                       _ = eventLog.update(consumeRef +: _)

                       channelToIndexedData <- fetchChannelToIndexData(channels)

                       options = extractDataCandidates(
                         channels.zip(patterns),
                         channelToIndexedData,
                         Nil
                       ).sequence.map(_.sequence)

                       result <- options match {
                                  case Left(e) =>
                                    e.asLeft[MaybeDataCandidate].pure[F]
                                  case Right(None) => storeWC(consumeRef)
                                  case Right(Some(dataCandidates)) =>
                                    for {
                                      _ <- metricsF.incrementCounter(consumeCommLabel)
                                      _ = eventLog.update(
                                        COMM(consumeRef, dataCandidates.map(_.datum.source)) +: _
                                      )
                                      _ <- storePersistentData(dataCandidates)
                                      _ = logger.debug(
                                        s"consume: data found for <patterns: $patterns> at <channels: $channels>"
                                      )
                                    } yield wrapResult(consumeRef, dataCandidates)

                                }
                     } yield result

                   }
        } yield result
    }
  }

  private[this] def nextSequenceNumber(
      consumeRef: Consume,
      dataCandidates: Seq[DataCandidate[C, R]]
  ) =
    Math.max(
      consumeRef.sequenceNumber,
      dataCandidates.map {
        case DataCandidate(_, Datum(_, _, source), _) => source.sequenceNumber
      }.max
    ) + 1

  /*
   * Find produce candidate
   *
   * Could also be implemented with a lazy `foldRight`.
   */
  type MaybeProduceCandidate = Option[ProduceCandidate[C, P, R, K]]
  private[this] def extractProduceCandidate(
      groupedChannels: Seq[Seq[C]],
      batChannel: C,
      data: Datum[A]
  )(implicit m: Match[P, E, A, R]): F[Either[E, MaybeProduceCandidate]] = {
    type Accumulator = (Seq[Seq[C]], MaybeProduceCandidate, Option[E])
    implicit class RichAccumulator(acc: Accumulator) {
      def channels: Seq[Seq[C]]            = acc._1
      def candidate: MaybeProduceCandidate = acc._2
      def error: Option[E]                 = acc._3

      def result: Either[E, MaybeProduceCandidate] =
        acc match {
          case (_, _, Some(e)) => e.asLeft[MaybeProduceCandidate]
          case (_, mc, _)      => mc.asRight[E]
          case _               => None.asRight[E]
        }
    }
    object Accumulator {
      def apply(
          channels: Seq[Seq[C]],
          maybeCandidate: MaybeProduceCandidate,
          error: Option[E] = None
      ): Accumulator = new Accumulator(channels, maybeCandidate, error)
    }

    def continue(acc: Accumulator): Boolean =
      acc.channels.nonEmpty && acc.candidate.isEmpty && acc.error.isEmpty

    def go(acc: Accumulator): F[Accumulator] =
      acc.channels match {
        case Nil => Accumulator(Nil, None).pure[F]
        case channels :: remaining =>
          for {
            matchCandidates <- store.withTxnF(store.createTxnReadF()) { txn =>
                                Random.shuffle(
                                  store
                                    .getWaitingContinuation(txn, channels)
                                    .zipWithIndex
                                )
                              }
            /*
             * Here, we create a cache of the data at each channel as `channelToIndexedData`
             * which is used for finding matches.  When a speculative match is found, we can
             * remove the matching datum from the remaining data candidates in the cache.
             *
             * Put another way, this allows us to speculatively remove matching data without
             * affecting the actual store contents.
             *
             * In this version, we also add the produced data directly to this cache.
             */
            channelToIndexedDataList <- channels.traverse { c: C =>
                                         store
                                           .withTxnF(store.createTxnReadF()) { txn =>
                                             Random.shuffle(
                                               store
                                                 .getData(txn, Seq(c))
                                                 .zipWithIndex
                                             )
                                           }
                                           .map(
                                             as =>
                                               c -> {
                                                 if (c == batChannel)
                                                   (data, -1) +: as
                                                 else as
                                               }
                                           )
                                       }
            result <- extractFirstMatch(
                       channels,
                       matchCandidates,
                       channelToIndexedDataList.toMap
                     ) match {
                       case Left(e)     => Accumulator(Nil, None, Some(e)).pure[F]
                       case Right(None) => Accumulator(remaining, None).pure[F]
                       case Right(produceCandidate) =>
                         Accumulator(remaining, produceCandidate).pure[F]
                     }
          } yield result
      }
    Concurrent[F].iterateWhileM(Accumulator(groupedChannels, None))(go)(continue).map(_.result)
  }

  private[this] def processMatchFound(
      pc: ProduceCandidate[C, P, R, K]
  ): F[Either[E, Some[(ContResult[C, P, K], Seq[Result[R]])]]] =
    pc match {
      case ProduceCandidate(
          channels,
          WaitingContinuation(
            patterns,
            continuation,
            persistK,
            consumeRef
          ),
          continuationIndex,
          dataCandidates
          ) =>
        def registerCOMM =
          eventLog
            .update(
              COMM(consumeRef, dataCandidates.map(_.datum.source)) +: _
            )
            .pure[F]

        def maybePersistWaitingContinuation =
          if (!persistK) {
            store.withTxnF(store.createTxnWriteF()) { txn =>
              store.removeWaitingContinuation(
                txn,
                channels,
                continuationIndex
              )
            }
          } else ().pure[F]

        def removeMatchedDatumAndJoin =
          dataCandidates
            .sortBy(_.datumIndex)(Ordering[Int].reverse)
            .traverse {
              case DataCandidate(
                  candidateChannel,
                  Datum(_, persistData, _),
                  dataIndex
                  ) =>
                store.withTxnF(store.createTxnWriteF()) { txn =>
                  if (!persistData && dataIndex >= 0) {
                    store.removeDatum(
                      txn,
                      Seq(candidateChannel),
                      dataIndex
                    )
                  }
                  store.removeJoin(txn, candidateChannel, channels)
                }
            }

        def constructResult = {
          val contSequenceNumber = nextSequenceNumber(consumeRef, dataCandidates)
          Some(
            (
              ContResult[C, P, K](
                continuation,
                persistK,
                channels,
                patterns,
                contSequenceNumber
              ),
              dataCandidates.map(dc => Result(dc.datum.a, dc.datum.persist))
            )
          ).asRight[E]
        }

        for {
          _ <- metricsF.incrementCounter(produceCommLabel)
          _ <- registerCOMM
          _ <- maybePersistWaitingContinuation
          _ <- removeMatchedDatumAndJoin
          _ = logger.debug(
            s"produce: matching continuation found at <channels: $channels>"
          )
        } yield constructResult
    }

  private[this] def storeData(channel: C, data: A, persist: Boolean, produceRef: Produce) =
    for {
      _ <- logger.debug(s"produce: no matching continuation found").pure[F]
      _ <- store
            .withTxnF(store.createTxnWriteF()) { txn =>
              store.putDatum(txn, Seq(channel), Datum(data, persist, produceRef))
            }
      _ = logger.debug(s"produce: persisted <data: $data> at <channel: $channel>")
    } yield Right(None)

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements")) // TODO remove when Kamon replaced with Metrics API
  override def produce(channel: C, data: A, persist: Boolean, sequenceNumber: Int)(
      implicit m: Match[P, E, A, R]
  ): F[Either[E, Option[(ContResult[C, P, K], Seq[Result[R]])]]] =
    contextShift.evalOn(scheduler) {
      for {
        produceRef <- Produce.create(channel, data, persist, sequenceNumber).pure[F]
        result <- produceLockF(channel) {
                   for {
                     //TODO fix double join fetch
                     groupedChannels <- store.withTxnF(store.createTxnReadF()) {
                                         store.getJoin(_, channel)
                                       }
                     _ = logger.debug(
                       s"""|produce: searching for matching continuations
                           |at <groupedChannels: $groupedChannels>""".stripMargin.replace('\n', ' ')
                     )
                     _ = eventLog.update(produceRef +: _)
                     extracted <- extractProduceCandidate(
                                   groupedChannels,
                                   channel,
                                   Datum(data, persist, produceRef)
                                 )
                     r <- extracted match {
                           case Left(e)         => Left(e).pure[F]
                           case Right(Some(pc)) => processMatchFound(pc)
                           case Right(None) =>
                             storeData(channel, data, persist, produceRef)
                         }
                   } yield r
                 }
      } yield result
    }

  override def createCheckpoint(): F[Checkpoint] =
    syncF.delay {
      val root   = store.createCheckpoint()
      val events = eventLog.take()
      eventLog.put(Seq.empty)
      Checkpoint(root, events)
    }
}

object RSpace {

  def create[F[_], C, P, E, A, R, K](context: Context[F, C, P, A, K], branch: Branch)(
      implicit
      sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K],
      concurrent: Concurrent[F],
      log: Log[F],
      contextShift: ContextShift[F],
      scheduler: ExecutionContext,
      metricsF: Metrics[F]
  ): F[ISpace[F, C, P, E, A, R, K]] = {
    type InMemTXN    = InMemTransaction[history.State[Blake2b256Hash, GNAT[C, P, A, K]]]
    type ByteBuffTXN = Txn[ByteBuffer]

    context match {
      case ctx: LMDBContext[F, C, P, A, K] =>
        create(LMDBStore.create[F, C, P, A, K](ctx, branch), branch)

      case ctx: InMemoryContext[F, C, P, A, K] =>
        create(InMemoryStore.create[F, InMemTXN, C, P, A, K](ctx.trieStore, branch), branch)

      case ctx: MixedContext[F, C, P, A, K] =>
        create(
          LockFreeInMemoryStore.create[F, ByteBuffTXN, C, P, A, K](ctx.trieStore, branch),
          branch
        )
    }
  }

  def create[F[_], C, P, E, A, R, K](store: IStore[F, C, P, A, K], branch: Branch)(
      implicit
      sc: Serialize[C],
      sp: Serialize[P],
      sa: Serialize[A],
      sk: Serialize[K],
      concurrent: Concurrent[F],
      logF: Log[F],
      contextShift: ContextShift[F],
      scheduler: ExecutionContext,
      metricsF: Metrics[F]
  ): F[ISpace[F, C, P, E, A, R, K]] = {

    implicit val codecC: Codec[C] = sc.toCodec
    implicit val codecP: Codec[P] = sp.toCodec
    implicit val codecA: Codec[A] = sa.toCodec
    implicit val codecK: Codec[K] = sk.toCodec

    val space: ISpace[F, C, P, E, A, R, K] =
      new RSpace[F, C, P, E, A, R, K](store, branch)

    /*
     * history.initialize returns true if the history trie contains no root (i.e. is empty).
     *
     * In this case, we create a checkpoint for the empty store so that we can reset
     * to the empty store state with the clear method.
     */
    if (history.initialize(store.trieStore, branch)) {
      space.createCheckpoint().map(_ => space)
    } else {
      space.pure[F]
    }
  }
}
