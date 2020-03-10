package ch.epfl.bluebrain.nexus.rdf.iri

import cats.syntax.show._
import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.iri.Authority.Port

import scala.annotation.tailrec
import ch.epfl.bluebrain.nexus.rdf.iri.Iri._
import ch.epfl.bluebrain.nexus.rdf.iri.Path._

/**
  * An Iri as defined by RFC 3987.
  */
sealed abstract class Iri extends Product with Serializable {

  /**
    * @return true if this Iri is an (absolute) Uri, false otherwise
    */
  def isUri: Boolean

  /**
    * @return Some(this) if this Iri is an (absolute) Uri, None otherwise
    */
  def asUri: Option[Uri]

  /**
    * @return true if this Iri is relative, false otherwise
    */
  def isRelative: Boolean = !isUri

  /**
    * @return Some(this) if this Iri is relative, None otherwise
    */
  def asRelative: Option[RelativeIri]

  /**
    * @return true if this Iri is an Url, false otherwise
    */
  def isUrl: Boolean

  /**
    * @return Some(this) if this Iri is an Url, None otherwise
    */
  def asUrl: Option[Url]

  /**
    * @return true if this Iri is an Urn, false otherwise
    */
  def isUrn: Boolean

  /**
    * @return Some(this) if this Iri is an Urn, None otherwise
    */
  def asUrn: Option[Urn]

  /**
    * @return the string representation as a valid Iri
    *         (using percent-encoding only for delimiters)
    */
  def iriString: String

  /**
    * @return the string representation of this Iri as a valid Uri
    *         (using percent-encoding when required according to rfc3986)
    */
  def uriString: String

}

object Iri {

  /**
    * Attempt to construct a new Url from the argument validating the structure and the character encodings as per
    * RFC 3987.
    *
    * @param string the string to parse as an absolute url.
    * @return Right(url) if the string conforms to specification, Left(error) otherwise
    */
  final def url(string: String): Either[String, Url] =
    Url(string)

  /**
    * Attempt to construct a new Urn from the argument validating the structure and the character encodings as per
    * RFC 3987 and 8141.
    *
    * @param string the string to parse as an Urn.
    * @return Right(urn) if the string conforms to specification, Left(error) otherwise
    */
  final def urn(string: String): Either[String, Urn] =
    Urn(string)

  /**
    * Attempt to construct a new Uri (Url or Urn) from the argument as per the RFC 3987 and 8141.
    *
    * @param string the string to parse as an absolute iri.
    * @return Right(Uri) if the string conforms to specification, Left(error) otherwise
    */
  final def uri(string: String): Either[String, Uri] =
    Uri(string)

  /**
    * Attempt to construct a new RelativeIri from the argument as per the RFC 3987.
    *
    * @param string the string to parse as a relative iri
    * @return Right(RelativeIri) if the string conforms to specification, Left(error) otherwise
    */
  final def relative(string: String): Either[String, RelativeIri] =
    RelativeIri(string)

  /**
    * Attempt to construct a new Iri (Url, Urn or RelativeIri) from the argument as per the RFC 3987 and 8141.
    *
    * @param string the string to parse as an iri.
    * @return Right(Iri) if the string conforms to specification, Left(error) otherwise
    */
  final def apply(string: String): Either[String, Iri] =
    uri(string) orElse relative(string)

  @SuppressWarnings(Array("EitherGet"))
  final def unsafe(string: String): Iri =
    apply(string).fold(left => throw new IllegalArgumentException(left), identity)

  /**
    * A relative IRI.
    *
    * @param authority the optional authority part
    * @param path      the path part
    * @param query     an optional query part
    * @param fragment  an optional fragment part
    */
  final case class RelativeIri(
      authority: Option[Authority],
      path: Path,
      query: Option[Query],
      fragment: Option[Fragment]
  ) extends Iri {
    override def isUri: Boolean                  = false
    override def asUri: Option[Uri]              = None
    override def isUrl: Boolean                  = false
    override def isUrn: Boolean                  = false
    override def asUrn: Option[Urn]              = None
    override def asUrl: Option[Url]              = None
    override def asRelative: Option[RelativeIri] = Some(this)

    override lazy val iriString: String = {
      val a = authority.map("//" + _.iriString).getOrElse("")
      val q = query.map("?" + _.iriString).getOrElse("")
      val f = fragment.map("#" + _.iriString).getOrElse("")

      s"$a${path.iriString}$q$f"
    }

    override lazy val uriString: String = {
      val a = authority.map("//" + _.uriString).getOrElse("")
      val q = query.map("?" + _.uriString).getOrElse("")
      val f = fragment.map("#" + _.uriString).getOrElse("")

      s"$a${path.uriString}$q$f"
    }

    /**
      * Resolves a [[RelativeIri]] into an [[Uri]] with the provided ''base''.
      * The resolution algorithm is taken from the rfc3986 and
      * can be found in https://tools.ietf.org/html/rfc3986#section-5.2.
      *
      * Ex: given a base = "http://a/b/c/d;p?q" and a relative iri = "./g" the output will be
      * "http://a/b/c/g"
      *
      * @param base the base [[Uri]] from where to resolve the [[RelativeIri]]
      */
    def resolve(base: Uri): Uri =
      base match {
        case url: Url => resolveUrl(url)
        case urn: Urn => resolveUrn(urn)
      }

    private def resolveUrl(base: Url): Uri =
      authority match {
        case Some(_) =>
          Url(base.scheme, authority, path, query, fragment)
        case None =>
          val (p, q) = path match {
            case Empty =>
              base.path -> (if (query.isDefined) query else base.query)
            case _ if path.startWithSlash =>
              removeDotSegments(path) -> query
            case _ =>
              merge(base) -> query
          }
          base.copy(query = q, path = p, fragment = fragment)
      }

    private def resolveUrn(base: Urn): Uri = {
      val (p, q) = path match {
        case Empty =>
          base.nss -> (if (query.isDefined) query else base.q)
        case _ if path.startWithSlash =>
          path -> query
        case _ =>
          merge(base) -> query
      }
      base.copy(q = q, nss = p, fragment = fragment)
    }

    private def merge(base: Url): Path =
      if (base.authority.isDefined && base.path.isEmpty) Slash(path)
      else removeDotSegments(path.prepend(deleteLast(base.path), allowSlashDup = true))

    private def merge(base: Urn): Path =
      removeDotSegments(path.prepend(deleteLast(base.nss), allowSlashDup = true))

    private def deleteLast(path: Path, withSlash: Boolean = false): Path =
      path match {
        case Segment(_, Slash(rest)) if withSlash => rest
        case Segment(_, rest)                     => rest
        case _                                    => path
      }

    private def removeDotSegments(path: Path): Path = {

      @tailrec
      def inner(input: Path, output: Path): Path =
        input match {
          // -> "../" or "./"
          case Segment("..", Slash(rest)) => inner(rest, output)
          case Segment(".", Slash(rest))  => inner(rest, output)
          // -> "/./" or "/.",
          case Slash(Segment(".", Slash(rest))) => inner(Slash(rest), output)
          case Slash(Segment(".", rest))        => inner(Slash(rest), output)
          // -> "/../" or "/.."
          case Slash(Segment("..", Slash(rest))) => inner(Slash(rest), deleteLast(output, withSlash = true))
          case Slash(Segment("..", rest))        => inner(Slash(rest), deleteLast(output, withSlash = true))
          // only "." or ".."
          case Segment(".", Empty) | Segment("..", Empty) => inner(Path.Empty, output)
          // move
          case Slash(rest)      => inner(rest, Slash(output))
          case Segment(s, rest) => inner(rest, output / s)
          case Empty            => output
        }

      inner(path.reverse, Path.Empty)
    }

  }

  object RelativeIri {

    /**
      * Attempt to construct a new RelativeIri from the argument validating the structure and the character encodings as per
      * RFC 3987.
      *
      * @param string the string to parse as a relative IRI.
      * @return Right(url) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, RelativeIri] =
      new IriParser(string).parseRelative

    implicit final val relativeIriShow: Show[RelativeIri] = Show.show(_.iriString)

    implicit final val relativeIriEq: Eq[RelativeIri] = Eq.fromUniversalEquals
  }

  /**
    * An (absolute) Uri as defined by RFC 3986.
    */
  sealed abstract class Uri extends Iri {
    override def isUri: Boolean                  = true
    override def asUri: Option[Uri]              = Some(this)
    override def asRelative: Option[RelativeIri] = None

    /**
      * Appends the segment to the end of the ''path'' section of the current ''Uri''. If the path section ends with slash it
      * directly appends, otherwise it adds a slash before appending
      *
      * @param segment the segment to be appended to the end of the path section
      */
    def /(segment: String): Uri

    /**
      * Appends the path to the end of the ''path'' section of the current ''Uri''. If the path section ends with slash it
      * directly appends, otherwise it adds a slash before appending
      *
      * @param path the path to be appended to the end of the path section
      */
    def /(path: Path): Uri

    /**
      * @return the path from an [[Url]] or the nss from a [[Urn]]
      */
    def path: Path

    /**
      *
      * @return the optional [[Fragment]] of the Uri
      */
    def fragment: Option[Fragment]

    /**
      * Returns a copy of this Uri with the fragment value set to the argument fragment.
      *
      * @param fragment the fragment to replace
      * @return a copy of this Uri with the fragment value set to the argument fragment
      */
    def withFragment(fragment: Fragment): Uri
  }

  object Uri {

    /**
      * Attempt to construct a new Uri (Url or Urn) from the argument as per the RFC 3987 and 8141.
      *
      * @param string the string to parse as an (absolute) Uri.
      * @return Right(Uri) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Uri] =
      new IriParser(string).parseAbsolute

    @SuppressWarnings(Array("EitherGet"))
    final def unsafe(string: String): Uri =
      apply(string).fold(left => throw new IllegalArgumentException(left), identity)

    implicit final val absoluteIriShow: Show[Uri] = Show.show(_.iriString)
    implicit final val absoluteIriEq: Eq[Uri]     = Eq.fromUniversalEquals
  }

  /**
    * An absolute Url.
    *
    * @param scheme    the scheme part
    * @param authority an optional authority part
    * @param path      the path part
    * @param query     an optional query part
    * @param fragment  an optional fragment part
    */
  final case class Url(
      scheme: Scheme,
      authority: Option[Authority],
      path: Path,
      query: Option[Query],
      fragment: Option[Fragment]
  ) extends Uri {
    override def isUrl: Boolean     = true
    override def asUrl: Option[Url] = Some(this)
    override def isUrn: Boolean     = false
    override def asUrn: Option[Urn] = None
    @tailrec
    override def /(segment: String): Uri =
      if (segment.startsWith("/")) this / segment.drop(1)
      else if (path.endsWithSlash) copy(path = path / segment)
      else copy(path = path / segment)

    override def /(p: Path): Uri = copy(path = path / p)

    override def withFragment(fragment: Fragment): Url =
      copy(fragment = Some(fragment))

    override lazy val iriString: String = {
      val a = authority.map("//" + _.iriString).getOrElse("")
      val q = query.map("?" + _.iriString).getOrElse("")
      val f = fragment.map("#" + _.iriString).getOrElse("")

      s"${scheme.value}:$a${path.iriString}$q$f"
    }

    override lazy val uriString: String = {
      val a = authority.map("//" + _.uriString).getOrElse("")
      val q = query.map("?" + _.uriString).getOrElse("")
      val f = fragment.map("#" + _.uriString).getOrElse("")

      s"${scheme.value}:$a${path.uriString}$q$f"
    }
  }

  object Url {

    private val defaultSchemePortMapping: Map[Scheme, Port] = Map(
      "ftp"    -> 21,
      "ssh"    -> 22,
      "telnet" -> 23,
      "smtp"   -> 25,
      "domain" -> 53,
      "tftp"   -> 69,
      "http"   -> 80,
      "ws"     -> 80,
      "pop3"   -> 110,
      "nntp"   -> 119,
      "imap"   -> 143,
      "snmp"   -> 161,
      "ldap"   -> 389,
      "https"  -> 443,
      "wss"    -> 443,
      "imaps"  -> 993,
      "nfs"    -> 2049
    ).map { case (s, p) => (new Scheme(s), new Port(p)) }

    private def normalize(scheme: Scheme, authority: Authority): Authority =
      if (authority.port == defaultSchemePortMapping.get(scheme)) authority.copy(port = None) else authority

    /**
      * Constructs an Url from its constituents.
      *
      * @param scheme    the scheme part
      * @param authority an optional authority part
      * @param path      the path part
      * @param query     an optional query part
      * @param fragment  an optional fragment part
      */
    final def apply(
        scheme: Scheme,
        authority: Option[Authority],
        path: Path,
        query: Option[Query],
        fragment: Option[Fragment]
    ): Url = new Url(scheme, authority.map(normalize(scheme, _)), path, query, fragment)

    /**
      * Attempt to construct a new Url from the argument validating the structure and the character encodings as per
      * RFC 3987.
      *
      * @param string the string to parse as an absolute url.
      * @return Right(url) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Url] =
      new IriParser(string).parseUrl

    @SuppressWarnings(Array("EitherGet"))
    private[rdf] def unsafe(string: String): Url =
      apply(string).fold(left => throw new IllegalArgumentException(left), identity)

    implicit final def urlShow: Show[Url] = Show.show(_.iriString)

    implicit final val urlEq: Eq[Url] = Eq.fromUniversalEquals
  }

  /**
    * An urn as defined by RFC 8141.
    *
    * @param nid      the namespace identifier
    * @param nss      the namespace specific string
    * @param r        the r component of the urn
    * @param q        the q component of the urn
    * @param fragment the f component of the urn, also known as fragment
    */
  final case class Urn(nid: Nid, nss: Path, r: Option[Component], q: Option[Query], fragment: Option[Fragment])
      extends Uri {
    override def isUrl: Boolean     = false
    override def asUrl: Option[Url] = None
    override def isUrn: Boolean     = true
    override def asUrn: Option[Urn] = Some(this)
    override val path: Path         = nss
    override def /(segment: String): Uri =
      if (nss.endsWithSlash) copy(nss = nss / segment) else copy(nss = nss / segment)

    override def /(p: Path): Uri = copy(nss = p / path)

    override def withFragment(fragment: Fragment): Urn =
      copy(fragment = Some(fragment))

    override lazy val iriString: String = {
      val rstr = r.map("?+" + _.iriString).getOrElse("")
      val qstr = q.map("?=" + _.iriString).getOrElse("")
      val f    = fragment.map("#" + _.iriString).getOrElse("")
      s"urn:${nid.iriString}:${nss.iriString}$rstr$qstr$f"
    }

    override lazy val uriString: String = {
      val rstr = r.map("?+" + _.uriString).getOrElse("")
      val qstr = q.map("?=" + _.uriString).getOrElse("")
      val f    = fragment.map("#" + _.uriString).getOrElse("")
      s"urn:${nid.uriString}:${nss.uriString}$rstr$qstr$f"
    }

  }

  object Urn {

    /**
      * Attempt to construct a new Urn from the argument validating the structure and the character encodings as per
      * RFC 3987 and 8141.
      *
      * @param string the string to parse as an Urn.
      * @return Right(urn) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Urn] =
      new IriParser(string).parseUrn

    @SuppressWarnings(Array("EitherGet"))
    private[rdf] def unsafe(string: String): Urn =
      apply(string).fold(left => throw new IllegalArgumentException(left), identity)

    implicit final val urnShow: Show[Urn] = Show.show(_.iriString)

    implicit final val urnEq: Eq[Urn] = Eq.fromUniversalEquals
  }

  implicit final val iriEq: Eq[Iri] = Eq.fromUniversalEquals
  implicit final def iriShow(implicit urnShow: Show[Urn], urlShow: Show[Url], relShow: Show[RelativeIri]): Show[Iri] =
    Show.show {
      case r: RelativeIri => r.show
      case url: Url       => url.show
      case urn: Urn       => urn.show
    }
}
