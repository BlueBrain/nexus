package ch.epfl.bluebrain.nexus.rdf.iri

import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.PctString._
import ch.epfl.bluebrain.nexus.rdf.iri.IriParser._
import ch.epfl.bluebrain.nexus.rdf.iri.Path._

import scala.annotation.tailrec

/**
  * Path part of an Iri as defined in RFC 3987.
  */
sealed abstract class Path extends Product with Serializable {

  /**
    * The type of the Head element of this path
    */
  type Head

  /**
    * @return true if the path contains no characters, false otherwise
    */
  def isEmpty: Boolean

  /**
    * @return the [[Head]] element of this [[Path]]
    */
  def head: Head

  /**
    * @param dropSlash a flag to decide whether it should skip slashes or not when returning the tail element
    * @return the remainder of this [[Path]] after subtracting its head.
    */
  def tail(dropSlash: Boolean = false): Path

  /**
    * @return false if the path contains no characters, true otherwise
    */
  def nonEmpty: Boolean = !isEmpty

  /**
    * @return true if this path is a [[Path.Slash]] (ends with a slash '/'), false otherwise
    */
  def isSlash: Boolean

  /**
    * @return true if this path is a [[Path.Segment]] (ends with a segment), false otherwise
    */
  def isSegment: Boolean

  /**
    * @return Some(this) if this path is an Empty path, None otherwise
    */
  def asEmpty: Option[Empty]

  /**
    * @return Some(this) if this path is a Slash (ends with a slash '/') path, None otherwise
    */
  def asSlash: Option[Slash]

  /**
    * @return Some(this) if this path is a Segment (ends with a segment) path, None otherwise
    */
  def asSegment: Option[Segment]

  /**
    * @return true if this path ends with a slash ('/'), false otherwise
    */
  def endsWithSlash: Boolean = isSlash

  /**
    * @return true if this path starts with a slash ('/'), false otherwise
    */
  def startWithSlash: Boolean

  /**
    * @param other the other path
    * @return true if this path starts with the provided ''other'' path, false otherwise
    */
  def startsWith(other: Path): Boolean = {
    @tailrec
    @SuppressWarnings(Array("ComparingUnrelatedTypes"))
    def inner(remainingCurr: Path, remainingOther: Path): (Path, Path) = {
      if (remainingOther.isEmpty) remainingCurr -> remainingOther
      else if (remainingCurr.head == remainingOther.head) inner(remainingCurr.tail(), remainingOther.tail())
      else remainingCurr -> remainingOther
    }
    val (_, otherResult) = inner(this.reverse, other.reverse)
    otherResult.isEmpty
  }

  /**
    * @return the string representation for the Path segment compatible with the rfc3987
    *         (using percent-encoding only for delimiters)
    */
  def iriString: String

  /**
    * @return the string representation using percent-encoding for the Path segment
    *         when necessary according to rfc3986
    */
  def uriString: String

  /**
    * @return the reversed path
    */
  def reverse: Path = {
    @tailrec
    def inner(acc: Path, remaining: Path): Path = remaining match {
      case Empty         => acc
      case Segment(h, t) => inner(Segment(h, acc), t)
      case Slash(t)      => inner(Slash(acc), t)
    }
    inner(Empty, this)
  }

  /**
    * Adds the ''other'' path to the end of the current path. This operation does not
    * create double slashes when merging paths.
    *
    * @param other the path to be suffixed to the current path
    * @return the current path plus the provided path
    *         Ex: current = "/a/b/c/d", other = "/e/f" will output "/a/b/c/d/e/f"
    *         current = "/a/b/c/def/", other = "/ghi/f" will output "/a/b/c/def/ghi/f"
    */
  def /(other: Path): Path = other.prepend(this, allowSlashDup = false)

  /**
    * @param other         the path to be prepended to the current path
    * @param allowSlashDup a flag to decide whether or not we allow slash duplication on path merging
    * @return the current path plus the provided path
    *         Ex: current = "/e/f", other = "/a/b/c/d" will output "/a/b/c/d/e/f"
    *         current = "ghi/f", other = "/a/b/c/def" will output "/a/b/c/def/ghi/f"
   **/
  def prepend(other: Path, allowSlashDup: Boolean = false): Path

  /**
    * @param segment the segment to be appended to a path
    * @return current / segment. If the current path is a [[Segment]], a slash will be added

    */
  def /(segment: String): Path

  /**
    * Converts the current path to a list of segments. A `/` is not counted as a segment.
    * E.g.: /a/b//c/d will be converted to List("a", "b", "c", "d")
    *
    * @return a list of segments represented as string
    */
  def segments: Seq[String]

  /**
    * Returns the last segment, if any. A `/` is not counted as a segment.
    * Example: /a/b/c/ will return "c"
    *
    * @return the last segment.
    */
  def lastSegment: Option[String]

  /**
    * Number of segments present on the current path. A `/` is not counted as a segment.
    * E.g.: `/a/b//c/d` will have 4 segments. The same as `/a/b/c/d`
    */
  def size: Int
}

object Path {

  /**
    * Constant value for a single slash.
    */
  final val / = Slash(Empty)

  /**
    * Attempts to parse the argument string as an `ipath-abempty` Path as defined by RFC 3987.
    *
    * @param string the string to parse as a Path
    * @return Right(Path) if the parsing succeeds, Left(error) otherwise
    */
  final def apply(string: String): Either[String, Path] =
    abempty(string)

  /**
    * Attempts to parse the argument string as an `ipath-abempty` Path as defined by RFC 3987.
    *
    * @param string the string to parse as a Path
    * @return Right(Path) if the parsing succeeds, Left(error) otherwise
    */
  final def abempty(string: String): Either[String, Path] =
    new IriParser(string).parsePathAbempty

  /**
    * Attempts to parse the argument string as an `ipath-rootless` Path as defined by RFC 3987.
    * A rootless path is a path which does not start with slash
    *
    * @param string the string to parse as a Path
    * @return Right(Path) if the parsing succeeds, Left(error) otherwise
    */
  final def rootless(string: String): Either[String, Path] =
    new IriParser(string).parsePathRootLess

  /**
    * Attempts to parse the argument string as an `isegment-nz` Segment as defined by RFC 3987.
    *
    * @param string the string to parse as a Path
    * @return Right(Path) if the parsing succeeds, Left(error) otherwise
    */
  final def segment(string: String): Either[String, Path] =
    new IriParser(string).parsePathSegment

  /**
    * An empty path.
    */
  sealed trait Empty extends Path

  /**
    * An empty path.
    */
  final case object Empty extends Empty {
    type Head = this.type
    def isEmpty: Boolean                                   = true
    def head: Head                                         = this
    def tail(dropSlash: Boolean): Path                     = this
    def isSlash: Boolean                                   = false
    def isSegment: Boolean                                 = false
    def asEmpty: Option[Empty]                             = Some(this)
    def asSlash: Option[Slash]                             = None
    def asSegment: Option[Segment]                         = None
    def iriString: String                                  = ""
    def uriString: String                                  = ""
    def startWithSlash: Boolean                            = false
    def prepend(other: Path, allowSlashDup: Boolean): Path = other
    def /(segment: String): Path                           = if (segment.isEmpty) this else Segment(segment, this)
    def segments: Seq[String]                              = Vector.empty
    def lastSegment: Option[String]                        = None
    def size: Int                                          = 0
  }

  /**
    * A path that ends with a '/' character.
    *
    * @param rest the remainder of the path (excluding the '/')
    */
  final case class Slash(rest: Path) extends Path {
    type Head = Char
    def isEmpty: Boolean               = false
    def head                           = '/'
    def tail(dropSlash: Boolean): Path = if (dropSlash && rest.endsWithSlash) rest.tail(dropSlash) else rest
    def isSlash: Boolean               = true
    def isSegment: Boolean             = false
    def asEmpty: Option[Empty]         = None
    def asSlash: Option[Slash]         = Some(this)
    def asSegment: Option[Segment]     = None
    def iriString: String              = rest.iriString + "/"
    def uriString: String              = rest.uriString + "/"
    def startWithSlash: Boolean        = if (rest.isEmpty) true else rest.startWithSlash
    @tailrec
    def prepend(other: Path, allowSlashDup: Boolean): Path =
      if (!allowSlashDup && other.endsWithSlash) prepend(other.tail(), allowSlashDup)
      else Slash(rest.prepend(other, allowSlashDup))
    def /(segment: String): Path    = if (segment.isEmpty) this else Segment(segment, this)
    def segments: Seq[String]       = rest.segments
    def lastSegment: Option[String] = rest.lastSegment
    def size: Int                   = rest.size
  }

  /**
    * A path that ends with a segment.
    *
    * @param rest the remainder of the path (excluding the segment denoted by this)
    */
  final case class Segment private[iri] (segment: String, rest: Path) extends Path {
    type Head = String
    def isEmpty: Boolean                                   = false
    def head: String                                       = segment
    def tail(dropSlash: Boolean): Path                     = if (dropSlash && rest.endsWithSlash) rest.tail(dropSlash) else rest
    def isSlash: Boolean                                   = false
    def isSegment: Boolean                                 = true
    def asEmpty: Option[Empty]                             = None
    def asSlash: Option[Slash]                             = None
    def asSegment: Option[Segment]                         = Some(this)
    def iriString: String                                  = rest.iriString + segment.pctEncodeIgnore(`ipchar_preds`)
    def uriString: String                                  = rest.uriString + segment.pctEncodeIgnore(`pchar_preds`)
    def startWithSlash: Boolean                            = rest.startWithSlash
    def prepend(other: Path, allowSlashDup: Boolean): Path = rest.prepend(other, allowSlashDup) / segment
    def /(s: String): Path                                 = if (segment.isEmpty) this else Segment(s, Slash(this))
    def segments: Seq[String]                              = rest.segments :+ segment
    def lastSegment: Option[String]                        = Some(segment)
    def size: Int                                          = 1 + rest.size
  }

  final implicit val pathShow: Show[Path] = Show.show(_.iriString)

  final implicit val pathEq: Eq[Path] = Eq.fromUniversalEquals
}
