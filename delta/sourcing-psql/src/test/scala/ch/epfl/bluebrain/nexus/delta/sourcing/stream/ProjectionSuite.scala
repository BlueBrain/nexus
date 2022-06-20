package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.{Chain, NonEmptyChain}
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemCtx.SourceIdPipeChainId
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.FailEveryN.FailEveryNConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Naturals.NaturalsConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.TimesN.TimesNConfig
import ch.epfl.bluebrain.nexus.testkit.{CollectionAssertions, EitherAssertions, MonixBioSuite}
import fs2.concurrent.Queue
import io.circe.JsonObject
import monix.bio.Task

import scala.concurrent.duration._

class ProjectionSuite extends MonixBioSuite with EitherAssertions with CollectionAssertions {
  val queue: Queue[Task, SuccessElem[String]] = Queue.unbounded[Task, SuccessElem[String]].runSyncUnsafe()
  val registry: ReferenceRegistry             = new ReferenceRegistry()
  registry.register(Naturals)
  registry.register(Strings)
  registry.register(Evens)
  registry.register(Odds)
  registry.register(TimesN)
  registry.register(FailEveryN)
  registry.register(IntToString)
  registry.register(Log(queue))

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    val _ = queue.tryDequeueChunk1(Int.MaxValue).runSyncUnsafe()
  }

  private def waitForNElements(count: Int, duration: FiniteDuration): Task[List[SuccessElem[String]]] =
    queue.dequeue.take(count.toLong).compile.toList.timeout(duration).map(_.getOrElse(Nil))

  private val emptyConfig = ExpandedJsonLd.unsafe(BNode.random, JsonObject.empty)

  private val evensPipe              = (Evens.reference, emptyConfig)
  private val oddsPipe               = (Odds.reference, emptyConfig)
  private val intToStringPipe        = (IntToString.reference, emptyConfig)
  private val logPipe                = (Log.reference, emptyConfig)
  private def timesNPipe(times: Int) = (TimesN.reference, TimesNConfig(times).toJsonLd)
  private def failNPipe(every: Int)  = (FailEveryN.reference, FailEveryNConfig(every).toJsonLd)

  test("Fail to compile SourceChain when source.Out does not match pipe.In") {
    SourceChain(
      Naturals.reference,
      iri"https://fail",
      NaturalsConfig(10, 2.second).toJsonLd,
      Chain(logPipe)
    ).compile(registry).assertLeft()
  }

  test("Fail to compile SourceChain when pipe.Out does not match pipe.In") {
    SourceChain(
      Naturals.reference,
      iri"https://fail",
      NaturalsConfig(10, 2.second).toJsonLd,
      Chain(evensPipe, logPipe)
    ).compile(registry).assertLeft()
  }

  test("Fail to compile PipeChain when pipe.Out does not match pipe.In") {
    PipeChain(
      iri"https://fail",
      NonEmptyChain(evensPipe, logPipe)
    ).compile(registry).assertLeft()
  }

  test("Fail to compile PipeChain when reference is not found in registry") {
    PipeChain(
      iri"https://fail",
      NonEmptyChain((PipeRef.unsafe("unknown"), emptyConfig))
    ).compile(registry).assertLeft()
  }

  test("Fail to compile SourceChain when reference is not found in registry") {
    SourceChain(
      SourceRef.unsafe("unknown"),
      iri"https://fail",
      NaturalsConfig(10, 2.second).toJsonLd,
      Chain.empty
    ).compile(registry).assertLeft()
  }

  test("Fail to compile SourceChain when configuration cannot be decoded") {
    SourceChain(
      Naturals.reference,
      iri"https://fail",
      ExpandedJsonLd.empty,
      Chain.empty
    ).compile(registry).assertLeft()
  }

  test("Fail to compile PipeChain when configuration cannot be decoded") {
    PipeChain(
      iri"https://fail",
      NonEmptyChain(TimesN.reference -> emptyConfig, intToStringPipe, logPipe)
    ).compile(registry).assertLeft()
  }

  test("Fail to compile PipeChain when the terminal type is not Unit") {
    PipeChain(
      iri"https://fail",
      NonEmptyChain(intToStringPipe)
    ).compile(registry).assertLeft()
  }

  test("All elements emitted by the sources should pass through the defined pipes") {
    // tests that multiple sources are merged correctly
    // tests that elements are correctly broadcast across multiple pipes
    // tests that offsets are passed to the sources
    // tests that projections stop when requested

    val sources  = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://evens",
        NaturalsConfig(10, 2.second).toJsonLd,
        Chain(evensPipe, timesNPipe(2))
      ),
      SourceChain(
        Naturals.reference,
        iri"https://odds",
        NaturalsConfig(7, 2.second).toJsonLd,
        Chain(oddsPipe)
      )
    )
    val pipes    = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(timesNPipe(2), intToStringPipe, logPipe)
      ),
      PipeChain(
        iri"https://log2",
        NonEmptyChain(intToStringPipe, failNPipe(2), logPipe)
      )
    )
    val defined  = ProjectionDef("naturals", None, None, sources, pipes)
    val compiled = defined.compile(registry).rightValue
    val offset   = ProjectionOffset(SourceIdPipeChainId(iri"https://evens", iri"https://log"), Offset.at(1L))

    // evens chain should emit before stop: 2*2, 4*2, 6*2, 8*2
    // odds chain should emit before stop: 1, 3, 5
    // log chain should see: 2*2*2, 4*2*2, 6*2*2, 8*2*2, 1*2, 3*2, 5*2
    // log2 chain should see 4 elements but nondeterministic depending on how the source elements are emitted
    // total elements in the queue: 11
    val expectedOffset = ProjectionOffset(
      Map(
        SourceIdPipeChainId(iri"https://evens", iri"https://log")  -> Offset.at(9L),
        SourceIdPipeChainId(iri"https://evens", iri"https://log2") -> Offset.at(9L),
        SourceIdPipeChainId(iri"https://odds", iri"https://log")   -> Offset.at(6L),
        SourceIdPipeChainId(iri"https://odds", iri"https://log2")  -> Offset.at(6L)
      )
    )

    for {
      projection <- compiled.start(offset)
      elements   <- waitForNElements(11, 500.millis)
      empty      <- waitForNElements(1, 50.millis)
      _           = assertEquals(empty.size, 0, "No other elements should be found after the first 11")
      _          <- projection.isRunning.assert(true)
      _          <- projection.stop()
      _          <- projection.isRunning.assert(false)
      values      = elements.map(_.value)
      _           = assertEquals(values.size, 11, "Exactly 11 elements should be observed on the queue")
      _           = values.assertContainsAllOf(Set(8, 16, 24, 32, 2, 6, 10).map(_.toString))
      elemsOfLog2 = elements.collect {
                      case e @ SuccessElem(SourceIdPipeChainId(_, pipeChain), _, _, _, _, _, _)
                          if pipeChain == iri"https://log2" =>
                        e
                    }
      _           = assertEquals(elemsOfLog2.size, 4, "Exactly 4 elements should pass through log2")
      _          <- projection.offset().assert(expectedOffset)
    } yield ()
  }

  test("Persist the current offset at regular intervals") {
    val sources  = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 50.millis).toJsonLd,
        Chain()
      )
    )
    val pipes    = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(intToStringPipe, logPipe)
      )
    )
    val defined  = ProjectionDef("naturals", None, None, sources, pipes)
    val compiled = defined.compile(registry).rightValue
    val offset   = ProjectionOffset(SourceIdPipeChainId(iri"https://naturals", iri"https://log"), Offset.at(2L))

    for {
      persistCallCountRef <- Ref.of[Task, (Int, ProjectionOffset)]((0, ProjectionOffset.empty))
      persistFn            = (po: ProjectionOffset) => persistCallCountRef.update { case (i, _) => (i + 1, po) }
      projection          <- compiled.persistOffset(persistFn, 5.millis).start(offset)
      _                   <- waitForNElements(10, 50.millis)
      _                   <- projection.stop()
      value               <- persistCallCountRef.get
      (count, observed)    = value
      _                    = assert(count > 1, "The persist fn should have been called at least twice.")
      _                    = assertNotEquals(
                               observed,
                               offset,
                               "The offsets observed by the persist fn should be different than the initial."
                             )
    } yield ()
  }

  test("Passivate a projection after becoming idle") {
    val sources  = NonEmptyChain(
      SourceChain(
        Naturals.reference,
        iri"https://naturals",
        NaturalsConfig(10, 1.second).toJsonLd,
        Chain()
      )
    )
    val pipes    = NonEmptyChain(
      PipeChain(
        iri"https://log",
        NonEmptyChain(intToStringPipe, logPipe)
      )
    )
    val defined  = ProjectionDef("naturals", None, None, sources, pipes)
    val compiled = defined.compile(registry).rightValue
    for {
      projection <- compiled.passivate(100.millis, 5.millis).start()
      _          <- waitForNElements(10, 50.millis)
      _          <- projection.isRunning.assert(true)
      _          <- Task.sleep(500.millis)
      _          <- projection.isRunning.assert(false)
    } yield ()
  }
}
