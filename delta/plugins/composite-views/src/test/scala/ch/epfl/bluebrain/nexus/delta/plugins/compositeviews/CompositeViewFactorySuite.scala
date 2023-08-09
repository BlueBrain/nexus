package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjectionFields.{ElasticSearchProjectionFields, SparqlProjectionFields}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.permissions
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectBase
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import io.circe.{Json, JsonObject}

import java.util.UUID

class CompositeViewFactorySuite extends BioSuite {

  implicit private val projectBase: ProjectBase = ProjectBase.unsafe(iri"http://localhost/project")
  private val uuid                              = UUID.randomUUID()
  implicit private val uuidF: UUIDF             = UUIDF.fixed(uuid)

  private val schemas: Set[IriOrBNode.Iri] = Set(nxv + "Schema")
  private val types: Set[IriOrBNode.Iri]   = Set(nxv + "Type")
  private val tag: Some[UserTag]           = Some(UserTag.unsafe("tag"))
  private val includeDeprecated            = true
  private val includeMetadata              = true
  private val includeContext               = true

  private val projectSourceId     = iri"http://localhost/project-source"
  private val projectSourceFields = ProjectSourceFields(
    Some(projectSourceId),
    schemas,
    types,
    tag,
    includeDeprecated
  )

  private val crossSourceId     = iri"http://localhost/cross-project-source"
  private val crossSourceFields = CrossProjectSourceFields(
    Some(crossSourceId),
    ProjectRef(Label.unsafe("org"), Label.unsafe("otherproject")),
    identities = Set(Identity.Anonymous),
    schemas,
    types,
    tag,
    includeDeprecated
  )

  private val remoteSourceId     = iri"http://localhost/remote-project-source"
  private val remoteSourceFields = RemoteProjectSourceFields(
    Some(remoteSourceId),
    ProjectRef.unsafe("org", "remoteproject"),
    Uri("http://example.com/remote-endpoint"),
    Some(Secret("secret token")),
    schemas,
    types,
    tag,
    includeDeprecated
  )

  private val esProjectionId     = iri"http://localhost/es-projection"
  private val esProjectionFields = ElasticSearchProjectionFields(
    Some(esProjectionId),
    SparqlConstructQuery.unsafe("CONSTRUCT..."),
    None,
    JsonObject("mapping" -> Json.obj()),
    ContextObject(JsonObject("context" -> Json.obj())),
    Some(JsonObject("settings" -> Json.obj())),
    schemas,
    types,
    tag,
    includeDeprecated,
    includeMetadata,
    includeContext
  )

  private val blazegraphProjectionId     = iri"http://example.com/blazegraph-projection"
  private val blazegraphProjectionFields = SparqlProjectionFields(
    Some(blazegraphProjectionId),
    SparqlConstructQuery.unsafe("CONSTRUCT..."),
    schemas,
    types,
    tag,
    includeDeprecated,
    includeMetadata
  )

  test("Create the matching source from the project source field with a defined id") {
    CompositeViewFactory
      .create(projectSourceFields)
      .assert(
        projectSourceId -> ProjectSource(projectSourceId, uuid, schemas, types, tag, includeDeprecated)
      )
  }

  test("Create the matching source from the project source field with no id") {
    val expectedId = projectBase.iri / uuid.toString
    CompositeViewFactory
      .create(projectSourceFields.copy(id = None))
      .assert(
        expectedId -> ProjectSource(expectedId, uuid, schemas, types, tag, includeDeprecated)
      )
  }

  test("Create the matching source from the cross project source field with a defined id") {
    CompositeViewFactory
      .create(crossSourceFields)
      .assert(
        crossSourceId -> CrossProjectSource(
          crossSourceId,
          uuid,
          schemas,
          types,
          tag,
          includeDeprecated,
          crossSourceFields.project,
          crossSourceFields.identities
        )
      )
  }

  test("Create the matching source from the remote project source field with a defined id") {
    CompositeViewFactory
      .create(remoteSourceFields)
      .assert(
        remoteSourceId -> RemoteProjectSource(
          remoteSourceId,
          uuid,
          schemas,
          types,
          tag,
          includeDeprecated,
          remoteSourceFields.project,
          remoteSourceFields.endpoint,
          remoteSourceFields.token.map(s => AccessToken(s))
        )
      )
  }

  test("Create the matching projection from the Elasticsearch projection field with a defined id") {
    CompositeViewFactory
      .create(esProjectionFields, 5)
      .assert(
        esProjectionId -> ElasticSearchProjection(
          esProjectionId,
          uuid,
          indexingRev = 5,
          esProjectionFields.query,
          schemas,
          types,
          tag,
          includeMetadata,
          includeDeprecated,
          includeContext,
          permissions.query,
          None,
          esProjectionFields.mapping,
          esProjectionFields.settings,
          esProjectionFields.context
        )
      )
  }

  test("Create the matching projection from the Blazegraph projection field with a defined id") {
    CompositeViewFactory
      .create(blazegraphProjectionFields, 5)
      .assert(
        blazegraphProjectionId -> SparqlProjection(
          blazegraphProjectionId,
          uuid,
          indexingRev = 5,
          blazegraphProjectionFields.query,
          schemas,
          types,
          tag,
          includeMetadata,
          includeDeprecated,
          permissions.query
        )
      )
  }

  test("Create a source when upserting a non-existing source") {
    CompositeViewFactory
      .upsert(projectSourceFields, _ => None)
      .assert(
        projectSourceId -> ProjectSource(projectSourceId, uuid, schemas, types, tag, includeDeprecated)
      )
  }

  test("Preserve the uuid when upserting an existing source") {
    val current = ProjectSource(projectSourceId, UUID.randomUUID(), schemas, types, tag, includeDeprecated)
    CompositeViewFactory
      .upsert(projectSourceFields, _ => Some(current))
      .map(_._2.uuid)
      .assert(current.uuid)
  }

  test("Create a projection when upserting a non-existing projection") {
    CompositeViewFactory
      .upsert(blazegraphProjectionFields, _ => None, 5, false)
      .assert(
        blazegraphProjectionId -> SparqlProjection(
          blazegraphProjectionId,
          uuid,
          indexingRev = 5,
          blazegraphProjectionFields.query,
          schemas,
          types,
          tag,
          includeMetadata,
          includeDeprecated,
          permissions.query
        )
      )
  }

  test("Preserve the uuid and the indexing rev when sources and projection have not changed.") {
    val current             = SparqlProjection(
      blazegraphProjectionId,
      uuid,
      indexingRev = 3,
      blazegraphProjectionFields.query,
      schemas,
      types,
      tag,
      includeMetadata,
      includeDeprecated,
      permissions.query
    )
    val expectedIndexingRev = 3
    CompositeViewFactory
      .upsert(blazegraphProjectionFields, _ => Some(current), 5, false)
      .map { case (_, p) =>
        p.uuid -> p.indexingRev
      }
      .assert(current.uuid -> expectedIndexingRev)
  }

  test("Preserve the uuid and update the indexing rev when source has changed.") {
    val current             = SparqlProjection(
      blazegraphProjectionId,
      uuid,
      indexingRev = 3,
      blazegraphProjectionFields.query,
      schemas,
      types,
      tag,
      includeMetadata,
      includeDeprecated,
      permissions.query
    )
    val expectedIndexingRev = 5
    CompositeViewFactory
      .upsert(blazegraphProjectionFields, _ => Some(current), 5, true)
      .map { case (_, p) =>
        p.uuid -> p.indexingRev
      }
      .assert(current.uuid -> expectedIndexingRev)
  }

  test("Preserve the uuid and update the indexing rev when the projection has changed.") {
    val current             = SparqlProjection(
      blazegraphProjectionId,
      uuid,
      indexingRev = 3,
      blazegraphProjectionFields.query,
      schemas,
      Set(nxv + "OldType"),
      tag,
      includeMetadata,
      includeDeprecated,
      permissions.query
    )
    val expectedIndexingRev = 5
    CompositeViewFactory
      .upsert(blazegraphProjectionFields, _ => Some(current), 5, false)
      .map { case (_, p) =>
        p.uuid -> p.indexingRev
      }
      .assert(current.uuid -> expectedIndexingRev)
  }
}
