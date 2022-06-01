package ch.epfl.bluebrain.nexus.delta.plugins.storage.utils

import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.RemoteContextResolutionFixture
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, StorageType}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import monix.execution.Scheduler

trait RouteFixtures extends TestHelpers with RemoteContextResolutionFixture {

  implicit val ordering: JsonKeyOrdering =
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )

  implicit val baseUri: BaseUri                   = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val paginationConfig: PaginationConfig = PaginationConfig(5, 10, 5)
  implicit val s: Scheduler                       = Scheduler.global
  implicit val rejectionHandler: RejectionHandler = RdfRejectionHandler.apply
  implicit val exceptionHandler: ExceptionHandler = RdfExceptionHandler.apply

  val realm: Label = Label.unsafe("wonderland")
  val alice: User  = User("alice", realm)
  val bob: User    = User("bob", realm)

  def storageMetadata(
      ref: ProjectRef,
      id: Iri,
      storageType: StorageType,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): Json =
    jsonContentOf(
      "storage/storage-route-metadata-response.json",
      "project"    -> ref,
      "id"         -> id,
      "rev"        -> rev,
      "deprecated" -> deprecated,
      "createdBy"  -> createdBy.id,
      "updatedBy"  -> updatedBy.id,
      "type"       -> storageType,
      "algorithm"  -> DigestAlgorithm.default,
      "label"      -> lastSegment(id)
    )

  def fileMetadata(
      ref: ProjectRef,
      id: Iri,
      attributes: FileAttributes,
      storage: ResourceRef.Revision,
      storageType: StorageType = StorageType.DiskStorage,
      rev: Long = 1L,
      deprecated: Boolean = false,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      label: Option[String] = None
  ): Json =
    jsonContentOf(
      "file/file-route-metadata-response.json",
      "project"     -> ref,
      "id"          -> id,
      "rev"         -> rev,
      "storage"     -> storage.iri,
      "storageType" -> storageType,
      "storageRev"  -> storage.rev,
      "bytes"       -> attributes.bytes,
      "digest"      -> attributes.digest.asInstanceOf[ComputedDigest].value,
      "algorithm"   -> attributes.digest.asInstanceOf[ComputedDigest].algorithm,
      "filename"    -> attributes.filename,
      "mediaType"   -> attributes.mediaType,
      "origin"      -> attributes.origin,
      "uuid"        -> attributes.uuid,
      "deprecated"  -> deprecated,
      "createdBy"   -> createdBy.id,
      "updatedBy"   -> updatedBy.id,
      "type"        -> storageType,
      "label"       -> label.fold(lastSegment(id))(identity)
    )

  private def lastSegment(iri: Iri) =
    iri.toString.substring(iri.toString.lastIndexOf("/") + 1)
}

object RouteFixtures extends RouteFixtures
