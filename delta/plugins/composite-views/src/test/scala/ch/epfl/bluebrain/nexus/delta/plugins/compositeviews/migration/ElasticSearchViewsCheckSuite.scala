package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import fs2.Stream
import io.circe.JsonObject
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant

class ElasticSearchViewsCheckSuite extends BioSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  test("Should init the extra tables") {
    MigrationCheckHelper.initTables(xas)
  }

  test("Save the count for the views") {
    def fetch(s: String) = Task.pure(s.length.toLong)
    val project          = ProjectRef.unsafe("org", "proj")
    val id1              = nxv + "view1"
    val view1            = ActiveViewDef(
      ViewRef(project, id1),
      projection = id1.toString,
      None,
      None,
      index = IndexLabel.unsafe("nexus_id1_1"),
      JsonObject.empty,
      JsonObject.empty,
      None,
      1
    )
    val id2              = nxv + "view2"
    val view2            = ActiveViewDef(
      ViewRef(project, id2),
      projection = id2.toString,
      None,
      None,
      index = IndexLabel.unsafe("nexus_id2_1"),
      JsonObject.empty,
      JsonObject.empty,
      None,
      1
    )

    def fetchViews: ElemStream[IndexingViewDef] =
      Stream.emits(List(view1, view2, view1.copy(index = IndexLabel.unsafe("nexus_id1_12")))).zipWithIndex.map {
        case (v, index) =>
          SuccessElem(
            tpe = ElasticSearchViews.entityType,
            id = v.ref.viewId,
            project = Some(project),
            instant = Instant.EPOCH,
            offset = Offset.at(index),
            value = v,
            revision = 1
          )
      }

    val check = new ElasticSearchViewsCheck(fetchViews, fetch, "delta", xas)

    for {
      _ <- check.run.compile.drain
      _ <- checkView(project, id1).assert((12L, 11L))
      _ <- checkView(project, id2).assert((11L, 11L))
    } yield ()
  }

  private def checkView(project: ProjectRef, viewId: Iri) =
    sql"""SELECT count_1_7,  count_1_8 FROM public.migration_elasticsearch_count WHERE project = $project and id = $viewId"""
      .query[(Long, Long)]
      .unique
      .transact(xas.read)

}
