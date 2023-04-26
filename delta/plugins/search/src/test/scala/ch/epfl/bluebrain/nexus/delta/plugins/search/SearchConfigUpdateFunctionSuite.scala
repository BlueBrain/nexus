package ch.epfl.bluebrain.nexus.delta.plugins.search

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, CompositeViewsFixture}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF, ResourceUris, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import io.circe.Json
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.DurationInt

class SearchConfigUpdateFunctionSuite extends BioSuite with CompositeViewsFixture with Fixtures with Doobie.Fixture {
  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)
  private lazy val xas                           = doobie()

  implicit private val s: Identity.User = subject
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val fetchContext = FetchContextDummy[CompositeViewRejection](
    Map(project.ref -> project.context),
    Set.empty[ProjectRef],
    ProjectContextRejection
  )

  private lazy val compositeViews = CompositeViews(
    fetchContext,
    ResolverContextResolution(rcr),
    alwaysValidate,
    crypto,
    config,
    xas
  )

  private val viewDef = ActiveViewDef(ViewRef(projectRef, id), uuid, 1, viewValue)

  private val newProjections: NonEmptySet[CompositeViewProjectionFields] =
    NonEmptySet.of(esProjectionFields)
  private val newSources: NonEmptySet[CompositeViewSourceFields]         =
    NonEmptySet.of(projectFields)
  private val newRebuildStrategy                                         =
    Some(Interval(2.minutes))
  private val newViewFields                                              =
    viewFields.copy(
      projections = newProjections,
      sources = newSources,
      rebuildStrategy = newRebuildStrategy
    )

  test("SearchConfigUpdater should run successfully") {
    for {
      views   <- compositeViews
      // Create the view
      created <- views.create(id, projectRef, viewFields)
      _        = assertEquals(
                   created.copy(value = created.value.copy(source = Json.obj())),
                   resourceFor(id, viewValue, source = Json.obj())
                 )
      // Start the updater
      updater  = SearchConfigUpdater.update(views)
      _       <- updater(viewDef, newViewFields)
      // Check that the update was successful

    } yield ()
  }

  test("Sources, projections, and rebuild strategy should be updated") {
    for {
      views   <- compositeViews
      updated <- views.fetch(id, projectRef)
      _        = assertEquals(updated.rev, 2)
      _        = assertEquals(updated.value.sources.map(_.toFields), newSources)
      _        = assertEquals(updated.value.projections.map(_.toFields), newProjections)
      _        = assertEquals(updated.value.rebuildStrategy, newRebuildStrategy)
    } yield ()
  }

  def resourceFor(
      id: Iri,
      value: CompositeViewValue,
      rev: Int = 1,
      deprecated: Boolean = false,
      createdAt: Instant = Instant.EPOCH,
      createdBy: Subject = subject,
      updatedAt: Instant = Instant.EPOCH,
      updatedBy: Subject = subject,
      tags: Tags = Tags.empty,
      source: Json
  ): ViewResource = {
    ResourceF(
      id,
      ResourceUris("views", projectRef, id)(project.apiMappings, project.base),
      rev,
      Set(nxv.View, compositeViewType),
      deprecated,
      createdAt,
      createdBy,
      updatedAt,
      updatedBy,
      schema,
      CompositeView(
        id,
        projectRef,
        value.sources,
        value.projections,
        value.rebuildStrategy,
        uuid,
        tags,
        source,
        Instant.EPOCH
      )
    )
  }
}
