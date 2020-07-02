package ch.epfl.bluebrain.nexus.kg.routes

import java.time.Instant
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import ch.epfl.bluebrain.nexus.admin.organizations.Organization
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.kg.config.Contexts.resourceCtxUri
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Projection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source.ProjectEventStream
import ch.epfl.bluebrain.nexus.kg.indexing.View.{CompositeView, ElasticSearchView, Filter, SparqlView}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.{Id, OrganizationRef, Ref}
import ch.epfl.bluebrain.nexus.kg.{urlEncode, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.util.Resources
import io.circe.Json
import io.circe.syntax._

trait RoutesFixtures extends TestHelper with Resources {

  val user                                = User("dmontero", "realm")
  implicit val caller: Caller             = Caller(user, Set(Anonymous))
  implicit val subject: Subject           = caller.subject
  implicit val token: Option[AccessToken] = Some(AccessToken("valid"))

  val oauthToken = OAuth2BearerToken("valid")

  val defaultPrefixMapping: Map[String, AbsoluteIri] = Map(
    "resource"        -> unconstrainedSchemaUri,
    "schema"          -> shaclSchemaUri,
    "view"            -> viewSchemaUri,
    "resolver"        -> resolverSchemaUri,
    "file"            -> fileSchemaUri,
    "storage"         -> storageSchemaUri,
    "nxv"             -> nxv.base,
    "documents"       -> nxv.defaultElasticSearchIndex.value,
    "graph"           -> nxv.defaultSparqlIndex.value,
    "defaultResolver" -> nxv.defaultResolver.value,
    "defaultStorage"  -> nxv.defaultStorage.value
  )

  val mappings: Map[String, AbsoluteIri] =
    Map(
      "nxv"      -> nxv.base,
      "resource" -> unconstrainedSchemaUri,
      "view"     -> viewSchemaUri,
      "resolver" -> resolverSchemaUri
    )

  val organization = genString(length = 4)
  val project      = genString(length = 4)

  // format: off
  val organizationMeta = ResourceF(genIri, genUUID, 1L, deprecated = false, Set.empty, Instant.EPOCH, subject, Instant.EPOCH, subject, Organization(organization, Some("description")))
  // format: on

  val organizationRef     = OrganizationRef(organizationMeta.uuid)
  val genUuid             = genUUID
  val projectRef          = ProjectRef(genUUID)
  val id                  = Id(projectRef, nxv.withSuffix(genUuid.toString).value)
  val urlEncodedId        = urlEncode(id.value)
  val urlEncodedIdNoColon = urlEncode(id.value).replace("%3A", ":")
  val label               = ProjectLabel(organization, project)

  // format: off
  val projectMeta = ResourceF(id.value, projectRef.id, 1L, deprecated = false, Set.empty, Instant.EPOCH, subject, Instant.EPOCH, subject, Project(project, organizationRef.id, organization, None, mappings, url"http://example.com/", nxv.base))
  val defaultEsView = ElasticSearchView(Json.obj(), Filter(), false, true, projectRef, nxv.defaultElasticSearchIndex.value, genUUID, 1L, false)
  val defaultSparqlView = SparqlView.default(projectRef)
  val sparqlProjection = SparqlProjection("", defaultSparqlView)
  val elasticSearchProjection = ElasticSearchProjection("", defaultEsView, Json.obj())
  val compositeViewSource = ProjectEventStream(genIri, Filter())
  val compositeView = CompositeView(Set(compositeViewSource), Set(sparqlProjection, elasticSearchProjection), None, projectRef, genIri, genUUID, 1L, deprecated = false)
  // format: on

  implicit val finalProject = projectMeta.copy(value =
    projectMeta.value.copy(apiMappings = projectMeta.value.apiMappings ++ defaultPrefixMapping)
  )

  def tag(rev: Long, tag: String) = Json.obj("tag" -> Json.fromString(tag), "rev" -> Json.fromLong(rev))

  def response(schema: Ref, deprecated: Boolean = false)(implicit config: ServiceConfig): Json =
    Json
      .obj(
        "@id"            -> Json.fromString(s"nxv:$genUuid"),
        "_constrainedBy" -> schema.iri.asJson,
        "_createdAt"     -> Json.fromString(Instant.EPOCH.toString),
        "_createdBy"     -> Json
          .fromString(s"${config.http.prefixIri.asUri}/realms/${user.realm}/users/${user.subject}"),
        "_deprecated"    -> Json.fromBoolean(deprecated),
        "_rev"           -> Json.fromLong(1L),
        "_project"       -> Json
          .fromString(s"${config.http.prefixIri.asUri}/projects/$organization/$project"),
        "_updatedAt"     -> Json.fromString(Instant.EPOCH.toString),
        "_updatedBy"     -> Json.fromString(s"${config.http.prefixIri.asUri}/realms/${user.realm}/users/${user.subject}")
      )
      .addContext(resourceCtxUri)

  def listingResponse()(implicit config: ServiceConfig): Json =
    Json.obj(
      "@context" -> Json.arr(
        Json.fromString("https://bluebrain.github.io/nexus/contexts/search.json"),
        Json.fromString("https://bluebrain.github.io/nexus/contexts/resource.json")
      ),
      "_total"   -> Json.fromInt(5),
      "_results" -> Json.arr(
        (1 to 5).map(i => {
          val id = s"${config.http.publicUri}/resources/$organization/$project/resource/resource:$i"
          jsonContentOf("/resources/es-metadata.json", Map(quote("{id}") -> id.toString))
        }): _*
      )
    )
}
