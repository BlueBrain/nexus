package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import fs2.Stream
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant

class BlazegraphViewsCheckSuite extends BioSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  test("Should init the extra tables") {
    MigrationCheckHelper.initTables(xas)
  }

  test("Save the count for the views") {
    def fetch(s: String) = Task.pure(s.length.toLong)
    val project          = ProjectRef.unsafe("org", "proj")
    val id1              = nxv + "view1"
    val view1            =
      ActiveViewDef(ViewRef(project, id1), projection = id1.toString, None, None, namespace = "nexus_id1_1", 1)
    val id2              = nxv + "view2"
    val view2            = ActiveViewDef(
      ViewRef(project, id2),
      projection = id2.toString,
      None,
      None,
      namespace = "nexus_id2_1",
      1
    )

    def fetchViews: ElemStream[IndexingViewDef] =
      Stream.emits(List(view1, view2, view1.copy(namespace = "nexus_id1_12"))).zipWithIndex.map { case (v, index) =>
        SuccessElem(
          tpe = BlazegraphViews.entityType,
          id = v.ref.viewId,
          project = Some(project),
          instant = Instant.EPOCH,
          offset = Offset.at(index),
          value = v,
          rev = 1
        )
      }

    val check = new BlazegraphViewsCheck(fetchViews, fetch, fetch, "delta", xas)

    for {
      _ <- check.run
      _ <- checkView(project, id1).assert((11L, 12L))
      _ <- checkView(project, id2).assert((11L, 11L))
    } yield ()
  }

  private def checkView(project: ProjectRef, viewId: Iri) =
    sql"""SELECT count_1_7,  count_1_8 FROM public.migration_blazegraph_count WHERE project = $project and id = $viewId"""
      .query[(Long, Long)]
      .unique
      .transact(xas.read)

}
