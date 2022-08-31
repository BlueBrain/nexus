package ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources

import cats.data.{Chain, NonEmptyChain}
import cats.effect.Resource
import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.{Latest, UserTag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.{ScopedStateStore, UniformScopedState}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources.ScopedStateSource.StateSourceConfig
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.bio.ResourceFixture.TaskFixture
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import fs2.concurrent.Queue
import monix.bio.Task

import java.time.Instant
import scala.concurrent.duration.DurationInt

class ScopedStateSourceSuite extends BioSuite with Doobie.Fixture {

  private val base = iri"http://localhost/"

  private val fixture = ResourceSuiteLocalFixture(
    "blocks",
    for {
      xas       <- Doobie.resource()
      qc         = QueryConfig(1, RefreshStrategy.Delay(500.millis))
      stateStore = ScopedStateStore(PullRequest.entityType, PullRequestState.serializer, qc, xas)
      rr         = new ReferenceRegistry
      queue     <- Resource.eval(Queue.unbounded[Task, SuccessElem[UniformScopedState]])
      _          = rr.register(ScopedStateSource(qc, xas, Set(PullRequestState.uniformScopedStateEncoder(base))))
      _          = rr.register(Log(queue))
      tc         = ProjectionTestContext(rr, queue)
    } yield (tc, xas, stateStore)
  )

  override def munitFixtures: Seq[TaskFixture[_]] = List(fixture)

  lazy val (ctx, xas, stateStore) = fixture()

  private val alice    = User("Alice", Label.unsafe("Wonderland"))
  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org2", "proj2")

  private val id1 = Label.unsafe("1")
  private val id2 = Label.unsafe("2")
  private val id3 = Label.unsafe("3")

  private val state1 = PullRequestActive(id1, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state2 = PullRequestActive(id2, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state3 = PullRequestActive(id1, project2, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state4 = PullRequestActive(id3, project3, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state5 = PullRequestActive(id1, project1, 2, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state6 = PullRequestActive(id2, project1, 2, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state7 = PullRequestActive(id3, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  def projectionFor(cfg: StateSourceConfig): ProjectionDef = {
    val sources = NonEmptyChain(
      SourceChain(
        SourceRef(ScopedStateSource.label),
        iri"https://uniform-scoped-states",
        cfg.toJsonLd,
        Chain()
      )
    )
    val pipes   = NonEmptyChain(
      PipeChain(iri"https://log", NonEmptyChain(ctx.logPipe))
    )
    ProjectionDef("uniform-scoped-states", Some(cfg.project), Some(iri"https://index"), sources, pipes)
  }

  def uniform(state: PullRequestState): UniformScopedState =
    PullRequestState.uniformScopedStateEncoder(base).toUniformScopedState(state)

  test("Save initial states") {
    for {
      _ <- List(state1, state2, state3, state4).traverse(s => stateStore.save(s).transact(xas.write))
      _ <- List(state5, state6).traverse(s => stateStore.save(s, UserTag.unsafe("mytag")).transact(xas.write))
    } yield ()
  }

  test("ScopedStateSource emits latest states from scoped_states") {
    val defined  = projectionFor(StateSourceConfig(project1, Latest))
    val compiled = defined.compile(ctx.registry).rightValue
    for {
      projection <- compiled.start()
      elems      <- ctx.waitForNElements(2, 200.millis)
      _          <- projection.stop()
      _           = elems.map(_.value).assertContainsAllOf(Set(uniform(state1), uniform(state2)))
    } yield ()
  }

  test("ScopedStateSource emits tagged states from scoped_states") {
    val defined  = projectionFor(StateSourceConfig(project1, UserTag.unsafe("mytag")))
    val compiled = defined.compile(ctx.registry).rightValue
    for {
      projection <- compiled.start()
      elems      <- ctx.waitForNElements(2, 200.millis)
      _          <- projection.stop()
      _           = elems.map(_.value).assertContainsAllOf(Set(uniform(state5), uniform(state6)))
    } yield ()
  }

  test("ScopedStateSource emits new states from scoped_states") {
    val defined  = projectionFor(StateSourceConfig(project1, Latest))
    val compiled = defined.compile(ctx.registry).rightValue
    for {
      projection <- compiled.start()
      elems      <- ctx.waitForNElements(2, 200.millis)
      _           = elems.map(_.value).assertContainsAllOf(Set(uniform(state1), uniform(state2)))
      _          <- stateStore.save(state7).transact(xas.write)
      newElems   <- ctx.waitForNElements(1, 200.millis)
      _           = newElems.map(_.value).assertContains(uniform(state7))
      _          <- projection.stop()
    } yield ()
  }

}
