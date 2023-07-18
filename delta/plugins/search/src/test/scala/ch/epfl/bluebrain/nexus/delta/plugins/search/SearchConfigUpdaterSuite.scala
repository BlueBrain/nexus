package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, CompositeViewsFixture}
import ch.epfl.bluebrain.nexus.delta.plugins.search.SearchScopeInitialization.defaultSearchCompositeViewFields
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import io.circe.JsonObject
import munit.AnyFixture

import scala.concurrent.duration.DurationInt

class SearchConfigUpdaterSuite
    extends BioSuite
    with SupervisorSetup.Fixture
    with CompositeViewsFixture
    with Fixtures
    with Doobie.Fixture {
  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie, supervisor)
  private lazy val xas                           = doobie()

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 10.millis)
  private lazy val (sv, _)                            = supervisor()

  implicit private val s: Identity.User = subject
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val defaults       = Defaults("viewName", "viewDescription")
  private val indexingConfig =
    IndexingConfig(
      Set.empty,
      JsonObject.empty,
      None,
      SparqlConstructQuery.unsafe(""),
      ContextObject(JsonObject.empty),
      None
    )

  private val proj1 = ProjectGen.project("org", "proj2")
  private val proj2 = ProjectGen.project("org", "proj1")

  private val fetchContext = FetchContextDummy[CompositeViewRejection](
    Map(proj2.ref -> project.context, proj1.ref -> project.context),
    Set.empty[ProjectRef],
    ProjectContextRejection
  )

  implicit private val randomUUID: UUIDF = UUIDF.random
  private lazy val compositeViews        =
    CompositeViews(
      fetchContext,
      ResolverContextResolution(rcr),
      alwaysValidate,
      crypto,
      config,
      xas
    )

  private val defaultViewFields = defaultSearchCompositeViewFields(defaults, indexingConfig)
  private val defaultViewValue  = CompositeViewValue(defaultViewFields, Map.empty, Map.empty, proj2.base)

  private def toValue(view: CompositeView): CompositeViewValue =
    CompositeViewValue(
      Some(defaults.name),
      Some(defaults.description),
      view.sources,
      view.projections,
      view.rebuildStrategy
    )

  test("A default view whose sources do not match the current config should be updated") {
    for {
      views   <- compositeViews
      _       <- views.create(defaultViewId, proj1.ref, viewFields)
      _       <- SearchConfigUpdater(sv, views, defaults, indexingConfig)
      default <- defaultViewValue
      _       <- views
                   .fetch(defaultViewId, proj1.ref)
                   .map(view => toValue(view.value))
                   .eventually(default)
    } yield ()
  }

  test("A non-default active view should not be updated") {
    for {
      views <- compositeViews
      _     <- views.create(id, proj1.ref, viewFields)
      _     <- SearchConfigUpdater(sv, views, defaults, indexingConfig)
      view  <- views.fetch(id, proj1.ref)
    } yield assertEquals(view.rev, 1)
  }

  test("A default view whose sources match the current config should not be updated") {
    for {
      views <- compositeViews
      _     <- views.create(defaultViewId, proj2.ref, defaultViewFields)
      _     <- SearchConfigUpdater(sv, views, defaults, indexingConfig)
      view  <- views.fetch(defaultViewId, proj2.ref)
    } yield assertEquals(view.rev, 1)
  }

}
