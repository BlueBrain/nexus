package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.ProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{permissions, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import fs2.Stream
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant
import java.util.UUID

class CompositeViewsCheckSuite extends BioSuite with Doobie.Fixture with TestHelpers {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  test("Should init the extra tables") {
    MigrationCheckHelper.initTables(xas)
  }

  test("Save the count for the views") {

    val source1Id     = iri"https://bbp.epfl.ch/source1"
    val projection1Id = iri"https://bbp.epfl.ch/projection1"
    val projection2Id = iri"https://bbp.epfl.ch/projection2"

    val projectSource =
      ProjectSource(source1Id, UUID.randomUUID(), Set.empty, Set.empty, None, includeDeprecated = false)

    val contextJson             = jsonContentOf("indexing/music-context.json")
    val elasticSearchProjection = ElasticSearchProjection(
      projection1Id,
      UUID.randomUUID(),
      SparqlConstructQuery.unsafe(""),
      resourceSchemas = Set.empty,
      resourceTypes = Set(iri"http://music.com/Band"),
      resourceTag = None,
      includeMetadata = false,
      includeDeprecated = false,
      includeContext = false,
      permissions.query,
      None,
      jsonObjectContentOf("indexing/mapping.json"),
      None,
      contextJson.topContextValueOrEmpty.asInstanceOf[ContextObject]
    )

    val blazegraphProjection = SparqlProjection(
      projection2Id,
      UUID.randomUUID(),
      SparqlConstructQuery.unsafe(""),
      resourceSchemas = Set.empty,
      resourceTypes = Set.empty,
      resourceTag = None,
      includeMetadata = false,
      includeDeprecated = false,
      permissions.query
    )

    val compositeValue = CompositeViewValue(
      NonEmptySet.of(projectSource),
      NonEmptySet.of(elasticSearchProjection, blazegraphProjection),
      None
    )

    val project = ProjectRef.unsafe("org", "proj")
    val id      = nxv + "id"
    val ref     = ViewRef(project, id)
    val uuid    = UUID.randomUUID()
    val rev     = 2
    val view    = ActiveViewDef(ref, uuid, rev, compositeValue)

    def fetchViews: ElemStream[CompositeViewDef] = Stream.emit(view).zipWithIndex.map { case (v, index) =>
      SuccessElem(
        tpe = CompositeViews.entityType,
        id = v.ref.viewId,
        project = Some(v.ref.project),
        instant = Instant.EPOCH,
        offset = Offset.at(index),
        value = v,
        revision = 1
      )
    }

    def fetch(s: String) = Task.pure(s.length.toLong)

    val check = new CompositeViewsCheck(fetchViews, fetch, fetch, fetch, "delta", "nexus", xas)

    for {
      _ <- check.run
      _ <- checkView(project, id, CompositeViewsCheck.commonSpaceId).assert((44L, 44L))
      _ <- checkView(project, id, projection1Id).assert((81L, 81L))
      _ <- checkView(project, id, projection2Id).assert((81L, 81L))
    } yield ()
  }

  private def checkView(project: ProjectRef, viewId: Iri, spaceId: Iri) =
    sql"""SELECT count_1_7,  count_1_8 FROM public.migration_composite_count WHERE project = $project and id = $viewId and space_id = $spaceId"""
      .query[(Long, Long)]
      .unique
      .transact(xas.read)

}
