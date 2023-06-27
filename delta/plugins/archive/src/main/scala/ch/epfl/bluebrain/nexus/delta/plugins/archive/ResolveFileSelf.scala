package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.InvalidFileLink
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import monix.bio.IO

trait ResolveFileSelf {
  def apply(self: String): IO[ArchiveRejection, (ProjectRef, ResourceRef)]
}

object ResolveFileSelf {
  def apply(fetchContext: FetchContext[ArchiveRejection])(implicit baseUri: BaseUri): ResolveFileSelf = {
    new ResolveFileSelf {
      override def apply(self: String): IO[ArchiveRejection, (ProjectRef, ResourceRef)] = {
        val baseUrl = baseUri.iriEndpoint.toString + "/files/"

        for {
          _             <- IO.raiseWhen(!self.startsWith(baseUrl))(
                             ArchiveRejection.InvalidFileLink(s"did not start with base '$baseUrl'")
                           )
          path           = self.stripPrefix(baseUrl)
          fileReference <- path.split('/').toList match {
                             case org :: project :: id :: Nil => fileReferenceFrom(org, project, UrlUtils.decode(id))
                             case _                           =>
                               IO.raiseError(
                                 ArchiveRejection.InvalidFileLink(
                                   s"parsing of path failed, expected org, project then id split by '/', recieved '$path'"
                                 )
                               )
                           }
        } yield fileReference
      }

      private def fileReferenceFrom(
          orgString: String,
          projectString: String,
          id: String
      ): IO[ArchiveRejection, (ProjectRef, ResourceRef)] = {
        for {
          projectRef  <- parseProjectRef(orgString, projectString)
          resourceRef <- parseResourceRef(id, projectRef)
        } yield {
          (projectRef, resourceRef)
        }
      }

      private def parseResourceRef(id: String, projectRef: ProjectRef): IO[ArchiveRejection, ResourceRef] = {
        for {
          projectContext <- fetchContext.onRead(projectRef)
          idIri          <- IO.fromOption(
                              IdSegment(id).toIri(projectContext.apiMappings, projectContext.base).map(ResourceRef(_)),
                              ArchiveRejection.InvalidFileLink(s"iri parsing failed for id '$id'")
                            )
        } yield idIri
      }

      private def parseProjectRef(orgString: String, projectString: String): IO[InvalidFileLink, ProjectRef] = {
        val projectRef = for {
          org     <- Label(orgString)
          project <- Label(projectString)
        } yield ProjectRef(org, project)

        IO.fromEither(projectRef)
          .mapError(e => ArchiveRejection.InvalidFileLink(s"project parsing failed: $e"))
      }
    }
  }

}
