package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.{Interval, RebuildStrategy}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.FilterDeprecated
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{NoopSink, Source}
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import fs2.Stream
import io.circe.Json
import monix.bio.Task
import shapeless.Typeable

import scala.concurrent.duration._

class CompositeViewDefSuite extends BioSuite with CompositeViewsFixture {

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(1.second, 50.millis)

  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.never

  private val sleep = Task.sleep(50.millis)

  test("Compile correctly the source") {

    def source(nameValue: String): Source = new Source {
      override type Out = Unit
      override def outType: Typeable[Unit]                 = Typeable[Unit]
      override def apply(offset: Offset): ElemStream[Unit] = Stream.empty[Task]
      override def name: String                            = nameValue
    }

    def toElemStream(run: Run): Source =
      run match {
        case Run.Main    => source("main")
        case Run.Rebuild => source("rebuild")
      }

    CompositeViewDef
      .compileSource(
        projectSource,
        _ => Right(FilterDeprecated.withConfig(())),
        (_, run) => toElemStream(run),
        new NoopSink[NTriples]()
      )
      .map { r => (r._1, r._2.name, r._3.name) }
      .assert((projectSource.id, "main", "rebuild"))
  }

  test("Compile correctly an Elasticsearch projection") {
    CompositeViewDef
      .compileTarget(
        esProjection,
        _ => Right(FilterDeprecated.withConfig(())),
        _ => Pipe.identity[GraphResource],
        _ => new NoopSink[Json]()
      )
      .map(_._1)
      .assert(esProjection.id)
  }

  private def rebuild(strategy: Option[RebuildStrategy]) = {
    for {
      start      <- Ref.of[Task, Boolean](false)
      value      <- Ref.of[Task, Int](0)
      rebuildWhen = Stream.awakeEvery[Task](50.millis).evalMap(_ => start.get)
      inc         = Stream.eval(value.getAndUpdate(_ + 1))
      result      = CompositeViewDef.rebuild(inc, strategy, rebuildWhen)
      _          <- result.compile.drain.start
    } yield (start, value)
  }

  test("Not execute the stream if no rebuild strategy is defined") {
    for {
      (start, value) <- rebuild(None)
      _              <- start.set(true)
      _              <- sleep
      _              <- value.get.assert(0)
    } yield ()
  }

  test("Execute the stream if a rebuild strategy is defined") {
    for {
      (start, value) <- rebuild(Some(Interval(50.millis)))
      _              <- start.set(true)
      _              <- value.get.eventually(4)
      // This should stop the stream
      _              <- start.set(false)
      _              <- sleep
      paused         <- value.get
      _              <- sleep
      _              <- value.get.assert(paused)
      // We resume the stream
      _              <- start.set(true)
      _              <- value.get.eventually(paused + 4)
    } yield ()
  }

}
