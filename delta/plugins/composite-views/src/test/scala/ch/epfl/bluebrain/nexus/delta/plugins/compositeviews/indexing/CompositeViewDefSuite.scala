package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.IO
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.{Interval, RebuildStrategy}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeGraphStream
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterDeprecated
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{NoopSink, RemainingElems, Source}
import ch.epfl.bluebrain.nexus.testkit.mu.bio.PatienceConfig
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import fs2.Stream
import shapeless.Typeable

import scala.concurrent.duration._

class CompositeViewDefSuite extends CatsEffectSuite with CompositeViewsFixture {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(1.second, 50.millis)

  private val sleep = IO.sleep(50.millis)

  test("Compile correctly the source") {

    def makeSource(nameValue: String): Source = new Source {
      override type Out = Unit
      override def outType: Typeable[Unit]                 = Typeable[Unit]
      override def apply(offset: Offset): ElemStream[Unit] = Stream.empty[IO]
      override def name: String                            = nameValue
    }

    val graphStream = new CompositeGraphStream {
      override def main(source: CompositeViewSource, project: ProjectRef): Source                                    = makeSource("main")
      override def rebuild(source: CompositeViewSource, project: ProjectRef, projectionTypes: Set[Iri]): Source      =
        makeSource("rebuild")
      override def remaining(source: CompositeViewSource, project: ProjectRef): Offset => IO[Option[RemainingElems]] =
        _ => IO.none
    }

    CompositeViewDef
      .compileSource(
        project.ref,
        _ => Right(FilterDeprecated.withConfig(())),
        graphStream,
        new NoopSink[NTriples](),
        Set.empty
      )(projectSource)
      .map { case (id, mainSource, rebuildSource, operation) =>
        assertEquals(id, projectSource.id)
        assertEquals(mainSource.name, "main")
        assertEquals(rebuildSource.name, "rebuild")
        assertEquals(operation.outType.describe, Typeable[GraphResource].describe)
      }
  }

  test("Compile correctly an Elasticsearch projection") {
    CompositeViewDef
      .compileTarget(
        _ => Right(FilterDeprecated.withConfig(())),
        _ => new NoopSink[GraphResource]()
      )(esProjection)
      .map(_._1)
      .assertEquals(esProjection.id)
  }

  test("Compile correctly a Sparql projection") {
    CompositeViewDef
      .compileTarget(
        _ => Right(FilterDeprecated.withConfig(())),
        _ => new NoopSink[GraphResource]()
      )(blazegraphProjection)
      .map(_._1)
      .assertEquals(blazegraphProjection.id)
  }

  private def rebuild(strategy: Option[RebuildStrategy]) = {
    for {
      start <- Ref.of[IO, Boolean](false)
      value <- Ref.of[IO, Int](0)
      reset <- Ref.of[IO, Int](0)
      inc    = Stream.eval(value.getAndUpdate(_ + 1))
      result = CompositeViewDef
                 .rebuild[Int](ViewRef(projectRef, id), strategy, start.get, reset.update(_ + 1))
      _     <- result(inc).compile.drain.start
    } yield (start, value, reset)
  }

  test("Not execute the stream if no rebuild strategy is defined") {
    for {
      (start, value, reset) <- rebuild(None)
      _                     <- start.set(true)
      _                     <- sleep
      _                     <- value.get.assertEquals(0)
      _                     <- reset.get.assertEquals(0)
    } yield ()
  }

  test("Execute the stream if a rebuild strategy is defined") {
    for {
      (start, value, reset) <- rebuild(Some(Interval(50.millis)))
      _                     <- start.set(true)
      _                     <- value.get.eventually(4)
      _                     <- reset.get.eventually(4)
      // This should stop the stream
      _                     <- start.set(false)
      _                     <- sleep
      paused                <- value.get
      _                     <- sleep
      _                     <- value.get.assertEquals(paused)
      // We resume the stream
      _                     <- start.set(true)
      _                     <- value.get.eventually(paused + 4)
    } yield ()
  }

}
