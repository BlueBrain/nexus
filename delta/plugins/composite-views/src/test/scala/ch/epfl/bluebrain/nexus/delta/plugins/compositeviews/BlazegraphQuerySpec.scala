package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryClientDummy
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{commonNamespace, projectionNamespace}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, ProjectionNotFound}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.ProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.ProjectionType.SparqlProjectionType
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.TemplateSparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.test.{expandOnlyIris, expectIndexingView}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.scalatest.bio.BioSpec
import io.circe.JsonObject
import org.scalatest.CancelAfterFailure

import java.util.UUID

class BlazegraphQuerySpec extends BioSpec with CancelAfterFailure {

  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val realm                = Label.unsafe("myrealm")
  private val alice: Caller        = Caller(User("Alice", realm), Set(User("Alice", realm), Group("users", realm)))
  implicit private val bob: Caller = Caller(User("Bob", realm), Set(User("Bob", realm), Group("users", realm)))
  private val anon: Caller         = Caller(Anonymous, Set(Anonymous))

  private val project   = ProjectGen.project("myorg", "proj")
  private val otherPerm = Permission.unsafe("other")

  private val aclCheck = AclSimpleCheck(
    (alice.subject, AclAddress.Project(project.ref), Set(permissions.query)),
    (bob.subject, AclAddress.Project(project.ref), Set(permissions.query, otherPerm)),
    (anon.subject, AclAddress.Root, Set(permissions.read))
  ).accepted

  private val construct = TemplateSparqlConstructQuery(
    "prefix p: <http://localhost/>\nCONSTRUCT{ {resource_id} p:transformed ?v } WHERE { {resource_id} p:predicate ?v}"
  ).rightValue

  private val id   = iri"http://localhost/${genString()}"
  private val uuid = UUID.randomUUID()

  private def blazeProjection(id: Iri, permission: Permission) =
    SparqlProjection(
      id,
      UUID.randomUUID(),
      IndexingRev.init,
      construct,
      Set.empty,
      Set.empty,
      false,
      false,
      permission
    )

  private val blazeProjection1 = blazeProjection(nxv + "blaze1", permissions.query)
  private val blazeProjection2 = blazeProjection(nxv + "blaze2", otherPerm)

  private val esProjection =
    ElasticSearchProjection(
      nxv + "es1",
      UUID.randomUUID(),
      IndexingRev.init,
      construct,
      Set.empty,
      Set.empty,
      false,
      false,
      false,
      permissions.query,
      None,
      JsonObject.empty,
      None,
      ContextObject(JsonObject.empty)
    )

  private val projectSource = ProjectSource(nxv + "source1", UUID.randomUUID(), Set.empty, Set.empty, None, false)

  private val indexingView = ActiveViewDef(
    ViewRef(project.ref, id),
    uuid,
    1,
    CompositeViewFactory.unsafe(
      NonEmptyList.of(projectSource),
      NonEmptyList.of(blazeProjection1, blazeProjection2, esProjection),
      None
    )
  )

  private val prefix = "prefix"

  // projection namespaces
  private val blazeP1Ns     = projectionNamespace(blazeProjection1, uuid, prefix)
  private val blazeP2Ns     = projectionNamespace(blazeProjection2, uuid, prefix)
  private val blazeCommonNs = commonNamespace(uuid, indexingView.value.sourceIndexingRev, prefix)

  private val responseCommonNs   = NTriples("blazeCommonNs", BNode.random)
  private val responseBlazeP1Ns  = NTriples("blazeP1Ns", BNode.random)
  private val responseBlazeP12Ns = NTriples("blazeP1Ns-blazeP2Ns", BNode.random)

  private val viewsQuery =
    BlazegraphQuery(
      aclCheck,
      expectIndexingView(indexingView),
      expandOnlyIris,
      new SparqlQueryClientDummy(sparqlNTriples = {
        case seq if seq.toSet == Set(blazeCommonNs)        => responseCommonNs
        case seq if seq.toSet == Set(blazeP1Ns)            => responseBlazeP1Ns
        case seq if seq.toSet == Set(blazeP1Ns, blazeP2Ns) => responseBlazeP12Ns
        case _                                             => NTriples.empty
      }),
      prefix
    )

  "A BlazegraphQuery" should {

    "query the common Blazegraph namespace" in {
      viewsQuery.query(id, project.ref, construct, SparqlNTriples).accepted.value shouldEqual responseCommonNs
      viewsQuery.query(id, project.ref, construct, SparqlNTriples)(alice).rejectedWith[AuthorizationFailed]
      viewsQuery.query(id, project.ref, construct, SparqlNTriples)(anon).rejectedWith[AuthorizationFailed]
    }

    "query all the Blazegraph projections' namespaces" in {
      forAll(List(alice -> responseBlazeP1Ns, bob -> responseBlazeP12Ns)) { case (caller, expected) =>
        viewsQuery.queryProjections(id, project.ref, construct, SparqlNTriples)(caller).accepted.value shouldEqual
          expected
      }
      viewsQuery.queryProjections(id, project.ref, construct, SparqlNTriples)(anon).rejectedWith[AuthorizationFailed]
    }

    "query a Blazegraph projections' namespace" in {
      val blaze1 = nxv + "blaze1"
      val es     = nxv + "es1"
      viewsQuery.query(id, blaze1, project.ref, construct, SparqlNTriples)(bob).accepted.value shouldEqual
        responseBlazeP1Ns
      viewsQuery.query(id, blaze1, project.ref, construct, SparqlNTriples)(anon).rejectedWith[AuthorizationFailed]
      viewsQuery.query(id, es, project.ref, construct, SparqlNTriples)(bob).rejected shouldEqual
        ProjectionNotFound(id, es, project.ref, SparqlProjectionType)
    }
  }

}
