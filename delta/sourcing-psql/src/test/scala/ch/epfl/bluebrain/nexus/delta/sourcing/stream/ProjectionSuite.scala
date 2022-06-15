package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.{Chain, NonEmptyChain}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemCtx.{SourceId, SourceIdPipeChainId}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.FailEveryN.FailEveryNConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Naturals.NaturalsConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.TimesN.TimesNConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.{CollectionAssertions, EitherAssertions, MonixBioSuite}
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
    ).compile(registry).assertLeft
  }

  test("Fail to compile SourceChain when pipe.Out does not match pipe.In") {
    SourceChain(
      Naturals.reference,
      iri"https://fail",
      NaturalsConfig(10, 2.second).toJsonLd,
      Chain(evensPipe, logPipe)
    ).compile(registry).assertLeft
  }

  test("Fail to compile PipeChain when pipe.Out does not match pipe.In") {
    PipeChain(
      iri"https://fail",
      NonEmptyChain(evensPipe, logPipe)
    ).compile(registry).assertLeft
  }

  test("Fail to compile PipeChain when reference is not found in registry") {
    PipeChain(
      iri"https://fail",
      NonEmptyChain((PipeRef.unsafe("unknown"), emptyConfig))
    ).compile(registry).assertLeft
  }

  test("Fail to compile SourceChain when reference is not found in registry") {
    SourceChain(
      SourceRef.unsafe("unknown"),
      iri"https://fail",
      NaturalsConfig(10, 2.second).toJsonLd,
      Chain.empty
    ).compile(registry).assertLeft
  }

  test("Fail to compile SourceChain when configuration cannot be decoded") {
    SourceChain(
      Naturals.reference,
      iri"https://fail",
      ExpandedJsonLd.empty,
      Chain.empty
    ).compile(registry).assertLeft
  }

  test("Fail to compile PipeChain when configuration cannot be decoded") {
    PipeChain(
      iri"https://fail",
      NonEmptyChain(TimesN.reference -> emptyConfig, intToStringPipe, logPipe)
    ).compile(registry).assertLeft
  }

  test("Fail to compile PipeChain when the terminal type is not Unit") {
    PipeChain(
      iri"https://fail",
      NonEmptyChain(intToStringPipe)
    ).compile(registry).assertLeft
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
    val defined  = ProjectionDef("naturals", sources, pipes, PassivationStrategy.Never, RebuildStrategy.Never)
    val compiled = defined.compile(registry).rightValue
    val offset   = ProjectionOffset(SourceId(iri"https://evens"), Offset.at(1L))

    // evens chain should emit before stop: 2*2, 4*2, 6*2, 8*2
    // odds chain should emit before stop: 1, 3, 5
    // log chain should see: 2*2*2, 4*2*2, 6*2*2, 8*2*2, 1*2, 3*2, 5*2
    // log2 chain should see 4 elements but nondeterministic depending on how the source elements are emitted
    // total elements in the queue: 11
    for {
      projection <- compiled.start(offset)
      elements   <- waitForNElements(11, 500.millis)
      empty      <- waitForNElements(1, 50.millis)
      _           = assertEquals(empty.size, 0, "No other elements should be found after the first 11")
      _          <- projection.stop()
      values      = elements.map(_.value)
      _           = assertEquals(values.size, 11, "Exactly 11 elements should be observed on the queue")
      _           = values.assertContainsAllOf(Set(8, 16, 24, 32, 2, 6, 10).map(_.toString))
      elemsOfLog2 = elements.collect {
                      case e @ SuccessElem(SourceIdPipeChainId(_, pipeChain), _) if pipeChain == iri"https://log2" => e
                    }
      _           = assertEquals(elemsOfLog2.size, 4, "Exactly 4 elements should pass through log2")
    } yield ()
  }
}
