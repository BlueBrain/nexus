package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server._
import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch.BatchFiles
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDestination, File, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.permissions.{read => Read}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts, FileResource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.ShowFileLocation
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.BulkOperationResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}

final class BatchFilesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    batchFiles: BatchFiles,
    index: IndexingAction.Execute[File]
)(implicit
    baseUri: BaseUri,
    showLocation: ShowFileLocation,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling { self =>

  private val logger = Logger[BatchFilesRoutes]

  implicit val bulkOpJsonLdEnc: JsonLdEncoder[BulkOperationResults[FileResource]] =
    BulkOperationResults.searchResultsJsonLdEncoder(ContextValue(contexts.files))

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("bulk") {
        pathPrefix("files") {
          extractCaller { implicit caller =>
            projectRef { project =>
              (post & pathEndOrSingleSlash & parameter("storage".as[IdSegment].?) & indexingMode & tagParam) {
                (storage, mode, tag) =>
                  // Bulk create files by copying from another project
                  entity(as[CopyFileSource]) { c: CopyFileSource =>
                    val copyTo = CopyFileDestination(project, storage, tag)
                    emit(Created, copyFiles(mode, c, copyTo))
                  }
              }
            }
          }
        }
      }
    }

  private def copyFiles(mode: IndexingMode, source: CopyFileSource, dest: CopyFileDestination)(implicit
      caller: Caller
  ): IO[Either[FileRejection, BulkOperationResults[FileResource]]] =
    (for {
      _       <-
        EitherT.right(aclCheck.authorizeForOr(source.project, Read)(AuthorizationFailed(source.project.project, Read)))
      results <- EitherT(batchFiles.copyFiles(source, dest).attemptNarrow[FileRejection])
      _       <- EitherT.right[FileRejection](results.traverse(index(dest.project, _, mode)))
      _       <- EitherT.right[FileRejection](logger.info(s"Bulk file copy succeeded with results: $results"))
    } yield BulkOperationResults(results.toList))
      .onError(e =>
        EitherT.right(logger.error(e)(s"Bulk file copy operation failed for source $source and destination $dest"))
      )
      .value
}
