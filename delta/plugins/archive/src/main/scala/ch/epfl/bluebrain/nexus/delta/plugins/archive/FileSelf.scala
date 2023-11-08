package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.archive.FileSelf.ParsingError._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

/**
  * Attempts to parse an incoming iri/uri as in order to extract file identifiers if it is a valid file "_self".
  *
  * Note that it does not verify if the file actually exists.
  */
trait FileSelf {

  def parse(input: Uri): IO[(ProjectRef, ResourceRef)] =
    parse(input.toIri)

  def parse(input: Iri): IO[(ProjectRef, ResourceRef)]
}

object FileSelf {

  /**
    * Enumeration of errors that can be raised while attempting to resolve a self
    */
  sealed trait ParsingError extends SDKError {
    def message: String

    override def getMessage: String = message
  }

  object ParsingError {

    /**
      * The context of the project containing the file can't be fetched
      */
    final case class InvalidProjectContext(input: Iri, project: ProjectRef) extends ParsingError {
      def message: String = s"Resolving self '$input' failed while retrieving context for project '$project'."
    }

    /**
      * The provided input is not a absolute link
      */
    final case class NonAbsoluteLink(input: Iri) extends ParsingError {
      def message: String = s"'$input' is not an absolute link."
    }

    /**
      * The provided input is an external link
      */
    final case class ExternalLink(input: Iri) extends ParsingError {
      def message: String = s"'$input' has been resolved as an external link."
    }

    /**
      * The provided self does
      */
    final case class InvalidPath(input: Iri) extends ParsingError {
      def message: String = s"'$input' does not provide the expected path."
    }

    /**
      * The provided input can't be parsed in a way to allow to extract a project reference
      */
    final case class InvalidProject(input: Iri) extends ParsingError {
      def message: String = s"No project could be parsed from '$input'."
    }

    /**
      * The provided input can't be parsed in a way to allow to extract a file identifier
      */
    final case class InvalidFileId(input: Iri) extends ParsingError {
      def message: String = s"No file @id could be parsed from '$input'."
    }
  }

  def apply(fetchContext: FetchContext[_])(implicit baseUri: BaseUri): FileSelf = {

    val filePrefixIri = baseUri.iriEndpoint / "files" / ""

    new FileSelf {
      override def parse(input: Iri): IO[(ProjectRef, ResourceRef)] =
        validateSelfPrefix(input) >> parseSelf(input)

      private def validateSelfPrefix(self: Iri) =
        if (self.isAbsolute)
          IO.raiseUnless(self.startsWith(filePrefixIri))(ExternalLink(self))
        else
          IO.raiseError(ParsingError.NonAbsoluteLink(self))

      private def parseSelf(self: Iri): IO[(ProjectRef, ResourceRef)] =
        self.stripPrefix(filePrefixIri).split('/') match {
          case Array(org, project, id) =>
            for {
              project        <- IO.fromEither(ProjectRef.parse(org, project).leftMap(_ => InvalidProject(self)))
              projectContext <- fetchContext.onRead(project).adaptError { _ => InvalidProjectContext(self, project) }
              decodedId       = UrlUtils.decode(id)
              iriOption       =
                IdSegment(decodedId).toIri(projectContext.apiMappings, projectContext.base).map(ResourceRef(_))
              resourceRef    <- IO.fromOption(iriOption)(InvalidFileId(self))
            } yield {
              (project, resourceRef)
            }
          case _                       => IO.raiseError(InvalidPath(self))
        }
    }
  }
}
