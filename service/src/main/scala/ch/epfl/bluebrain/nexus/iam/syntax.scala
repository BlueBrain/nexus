package ch.epfl.bluebrain.nexus.iam

import ch.epfl.bluebrain.nexus.iam.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.iam.types.Identity
import ch.epfl.bluebrain.nexus.iam.types.Identity._
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}

object syntax {

  implicit final def identitiesSyntax(identities: Set[Identity]): IdentitiesSyntax = new IdentitiesSyntax(identities)
  implicit final def absoluteIriSyntax(iri: AbsoluteIri): AbsoluteIriSyntax        = new AbsoluteIriSyntax(iri)

  final class IdentitiesSyntax(private val identities: Set[Identity]) extends AnyVal {
    private def findUser: Option[User]      = identities.collectFirst { case user: User      => user }
    private def findAnon: Option[Anonymous] = identities.collectFirst { case anon: Anonymous => anon }

    /**
      * Attempts to fetch the subject from the ''identities''. The subject is the first ''User'' found or the ''Anonymous'' identity.
      *
      * @return Some(identity) when the subject is contained in the ''identities'', None otherwise
      */
    def subject: Option[Identity] = findUser orElse findAnon
  }

  final class AbsoluteIriSyntax(private val iri: AbsoluteIri) extends AnyVal {
    def lastSegment: Option[String] =
      iri.path.head match {
        case segment: String => Some(segment)
        case _               => None
      }
  }

  implicit final class RichPath(private val path: Path) extends AnyVal {

    /**
      * @return parent segment or end slash.
      *         E.g.: /a/b returns /a
      *         E.g.: / returns /
      */
    def parent: Path = path.tail(dropSlash = path.tail() != Path./)

    /**
      * @return a fully qualified iri for the path (i.e.: https://nexus.example.com/v1/acls/my/path)
      */
    def toIri(implicit cfg: HttpConfig): AbsoluteIri =
      cfg.aclsIri + path
  }

}
