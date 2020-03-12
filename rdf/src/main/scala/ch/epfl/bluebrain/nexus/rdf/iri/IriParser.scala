package ch.epfl.bluebrain.nexus.rdf.iri

import java.lang.{StringBuilder => JStringBuilder}
import java.nio.charset.Charset

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.iri.Authority.Host._
import ch.epfl.bluebrain.nexus.rdf.iri.Authority._
import ch.epfl.bluebrain.nexus.rdf.iri.Curie._
import ch.epfl.bluebrain.nexus.rdf.iri.Iri._
import ch.epfl.bluebrain.nexus.rdf.iri.IriParser._
import ch.epfl.bluebrain.nexus.rdf.iri.Path._
import com.github.ghik.silencer.silent
import org.parboiled2.CharPredicate._
import org.parboiled2.Parser.DeliveryScheme.{Either => E}
import org.parboiled2._

// format: off
@SuppressWarnings(Array("MethodNames", "unused", "UnsafeTraversableMethods"))
@silent
private[iri] class IriParser(val input: ParserInput)
                            (implicit formatter: ErrorFormatter = new ErrorFormatter(showExpected = false, showTraces = false))
  extends Parser with StringBuilding {

  def parseAuthority: Either[String, Authority] =
  rule(`iauthority` ~ EOI).run()
    .map(_ => _authority)
    .leftMap(_.format(input, formatter))

  def parseScheme: Either[String, Scheme] =
    rule(scheme ~ EOI).run()
      .map(_ => _scheme)
      .leftMap(_.format(input, formatter))

  def parseIPv4: Either[String, IPv4Host] =
    rule(IPv4address ~ EOI).run()
      .map(_ => _host.asInstanceOf[IPv4Host])
      .leftMap(_.format(input, formatter))

  def parseNamed: Either[String, NamedHost] =
    rule(`ireg-name` ~ EOI).run()
      .map(_ => _host.asInstanceOf[NamedHost])
      .leftMap(_.format(input, formatter))

  def parsePort: Either[String, Port] =
    rule(`port` ~ EOI).run()
      .leftMap(_.format(input, formatter))
      .flatMap(_ => Port(_port))

  def parseUserInfo: Either[String, UserInfo] =
    rule(`iuserinfo` ~ EOI).run()
      .map(_ => _userInfo)
      .leftMap(_.format(input, formatter))

  def parsePathAbempty: Either[String, Path] =
    rule(`ipath-abempty` ~ EOI).run()
      .map(_ => _path)
      .leftMap(_.format(input, formatter))

  def parsePathRootLess: Either[String, Path] =
    rule(`ipath-rootless` ~ EOI).run()
      .map(_ => _path)
      .leftMap(_.format(input, formatter))

  def parsePathSegment: Either[String, Path] =
    rule(`isegment-nz` ~ EOI ~ push(getDecodedSB)).run()
      .map(str => Segment(str, Path.Empty))
      .leftMap(_.format(input, formatter))

  def parseQuery: Either[String, Query] =
    rule(`iquery` ~ EOI).run()
      .map(_ => _query)
      .leftMap(_.format(input, formatter))

  def parseFragment: Either[String, Fragment] =
    rule(`ifragment` ~ EOI).run()
      .map(_ => _fragment)
      .leftMap(_.format(input, formatter))

  def parseUrl: Either[String, Url] =
    rule(`url`).run()
      .map(_ => Url(
        scheme    = _scheme,
        authority = Option(_authority),
        path      = _path,
        query     = Option(_query),
        fragment  = Option(_fragment))
      )
      .leftMap(_.format(input, formatter))

  def parseUrn: Either[String, Urn] =
    rule(`urn`).run()
      .map(_ => Urn(
        nid      = _nid,
        nss      = _path,
        r        = Option(_r),
        q        = Option(_query),
        fragment = Option(_fragment))
      )
      .leftMap(_.format(input, formatter))

  def parseAbsolute: Either[String, Uri] =
    rule(`urn` | `url`).run()
      .map { _ =>
        if (_nid != null) Urn(
          nid      = _nid,
          nss      = _path,
          r        = Option(_r),
          q        = Option(_query),
          fragment = Option(_fragment))
        else Url(
          scheme    = _scheme,
          authority = Option(_authority),
          path      = _path,
          query     = Option(_query),
          fragment  = Option(_fragment))
      }
      .leftMap(_.format(input, formatter))

  def parseRelative: Either[String, RelativeIri] =
    rule(`irelative-ref` ~ EOI).run()
      .map(_ => RelativeIri(
        authority = Option(_authority),
        path      = _path,
        query     = Option(_query),
        fragment  = Option(_fragment))
      )
      .leftMap(_.format(input, formatter))

  def parseNid: Either[String, Nid] =
    rule(`nid` ~ EOI).run()
      .map(_ => _nid)
      .leftMap(_.format(input, formatter))

  def parseComponent: Either[String, Component] =
    rule(`component` ~ EOI).run()
      .leftMap(_.format(input, formatter))

  def parseCurie: Either[String, Curie] =
    rule(`curie`).run()
      .map { _ =>
        val prefix    = _ncName
        val reference = RelativeIri(
          authority = Option(_authority),
          path      = _path,
          query     = Option(_query),
          fragment  = Option(_fragment))
        Curie(prefix, reference)
      }
      .leftMap(_.format(input, formatter))

  def parseNcName: Either[String, Prefix] =
    rule(`nc-name` ~ EOI).run()
      .map(_ => _ncName)
      .leftMap(_.format(input, formatter))

  private def appendSBAsLower(): Rule0 = rule { run(sb.append(CharUtils.toLowerCase(lastChar))) }
  private def getDecodedSB: String = IriParser.decode(sb.toString, UTF8)

  private[this] var _scheme: Scheme = _
  private[this] var _host: Host = _
  private[this] var _port: Int = 0
  private[this] var _userInfo: UserInfo = _
  private[this] var _authority: Authority = _
  private[this] var _path: Path = Path.Empty
  private[this] var _query: Query = _
  private[this] var _fragment: Fragment = _

  private[this] var _nid: Nid = _
  private[this] var _r: Component = _

  private[this] var _ncName: Prefix = _

  private val schemeNonFirstPred = AlphaNum ++ "+-."
  private def scheme: Rule0 = rule {
    clearSB() ~ Alpha ~ appendSBAsLower() ~ zeroOrMore(schemeNonFirstPred ~ appendSBAsLower()) ~ run {
      _scheme = new Scheme(sb.toString)
    }
  }

  private val Digit04 = CharPredicate('0' to '4')
  private val Digit05 = CharPredicate('0' to '5')

  private def `dec-octet`: Rule1[Byte] = rule {
    capture(
      (ch('2') ~ ((Digit04 ~ Digit) | (ch('5') ~ Digit05)))
        | (ch('1') ~ Digit ~ Digit)
        | (Digit19 ~ Digit)
        | Digit
    ) ~> ((str: String) => java.lang.Integer.parseInt(str).toByte)
  }

  private def IPv4address: Rule0 = rule {
    clearSB() ~ `dec-octet` ~ "." ~ `dec-octet` ~ "." ~ `dec-octet` ~ "." ~ `dec-octet` ~> ((a: Byte, b: Byte, c: Byte, d: Byte) => {
      _host = IPv4Host(a, b, c, d)
    })
  }

  private def `pct-encoded`: Rule0 = rule {
    '%' ~ HexDigit ~ HexDigit ~ run {
      sb.append('%').append(charAt(-2)).append(lastChar)
    }
  }

  private def `ipchar`: Rule0 = rule {
    `ipchar_preds` ~ appendSB() | `pct-encoded`
  }

  private def `ireg-name`: Rule0 = rule {
    clearSB() ~ oneOrMore(`inamed_host_allowed` ~ appendSBAsLower() | `pct-encoded`) ~ run {
      _host = new NamedHost(getDecodedSB.toLowerCase)
    }
  }

  private def `port`: Rule0 = rule {
    Digit19 ~ run { _port = lastChar - '0' } ~ optional(
      Digit ~ run { _port = 10 * _port + (lastChar - '0') } ~ optional(
        Digit ~ run { _port = 10 * _port + (lastChar - '0') } ~ optional(
          Digit ~ run { _port = 10 * _port + (lastChar - '0') } ~ optional(
            Digit ~ run { _port = 10 * _port + (lastChar - '0') } )))) | '0'
  }

  private def `ihost`: Rule0 = rule { IPv4address | `ireg-name` }

  private def `iuserinfo`: Rule0 = rule {
    clearSB() ~ oneOrMore(`iuser_info_allowed` ~ appendSB() | `pct-encoded`) ~ run {
      _userInfo = new UserInfo(getDecodedSB)
    }
  }

  private def `iauthority`: Rule0 = rule {
    clearSB() ~ optional(`iuserinfo` ~ '@' | run { _userInfo = null }) ~ `ihost` ~ optional(':' ~ port) ~ run {
      _authority = Authority(Option(_userInfo), _host, if (_port == 0) None else Some(new Port(_port)))
    }
  }

  private def `ipath-abempty`: Rule0 = {
    def setPath(value: String): Unit = {
      val res = (_path, value) match {
        case (Segment(_, Slash(acc)), "..") => acc
        case (Slash(acc), "..")             => acc
        case (acc, "..")                    => acc
        case (acc, ".")                     => acc
        case (acc, el) if el.length == 0    => Slash(acc)
        case (acc, el)                      => Segment(el, Slash(acc))
      }
      _path = res
    }

    rule {
      zeroOrMore(clearSB() ~ '/' ~ `isegment` ~ run { setPath(getDecodedSB) })
    }
  }

  private def `ipath-absolute`: Rule0 = rule {
    clearSB() ~ '/' ~ optional(`isegment` ~ run {
      _path = if (sb.length() == 0) Slash(Path.Empty) else Segment(getDecodedSB, Slash(Path.Empty))
    } ~ `ipath-abempty`)
  }

  private def `ipath-noscheme`: Rule0 = {
    def setPath(value: String): Unit = {
      val res = (_path, value) match {
        case (Segment(el, Slash(acc)), "..") if el != ".."        => acc
        case (Segment(el, acc), "..") if el != ".." && el != "."  => acc
        case (Slash(acc), "..")                                   => acc
        case (acc, ".")                                           => acc
        case (acc, el) if el.length == 0                          => Slash(acc)
        case (Path.Empty, el)                                     => Segment(el, Path.Empty)
        case (acc, el)                                            => Segment(el, Slash(acc))
      }
      _path = res
    }

    rule {
      clearSB() ~ `isegment-nz-nc` ~ run {
        _path = Segment(getDecodedSB, Path.Empty)
      } ~ zeroOrMore(clearSB() ~ '/' ~ `isegment` ~ run { setPath(getDecodedSB) })
    }
  }

  private def `ipath-rootless`: Rule0 = rule {
    clearSB() ~ `isegment-nz` ~ run {
      _path = Segment(getDecodedSB, Path.Empty)
    } ~ `ipath-abempty`
  }

  private def `ipath-empty`: Rule0 = rule {
    clearSB() ~ MATCH ~ run {
      _path = Path.Empty
    }
  }

  private def `isegment`: Rule0 = rule { zeroOrMore(`ipchar`) }
  private def `isegment-nz`: Rule0 = rule { oneOrMore(`ipchar`) }
  private def `isegment-nz-nc`: Rule0 = rule { oneOrMore(!':' ~ `ipchar`) }
  private def `iquery`: Rule0 = {
    def part: Rule1[String] = rule {
      clearSB() ~ oneOrMore(!"?+" ~ ('+' ~ appendSB(' ') | `iquery_allowed` ~ appendSB() | `pct-encoded`)) ~ push(getDecodedSB)
    }

    /*_*/
    def entry: Rule1[(String, String)] = rule {
      part ~ optional('=' ~ part) ~> ((key: String, value: Option[String]) => (key, value.getOrElse("")))
    }
    /*_*/

    /*_*/
    rule {
      zeroOrMore(entry).separatedBy('&') ~> ((seq: Seq[(String, String)]) => _query = Query(seq))
    }
    /*_*/
  }

  private def `ifragment`: Rule0 = rule {
    clearSB() ~ zeroOrMore(`ipchar` | (CharPredicate("/?") ~ appendSB())) ~ run {
      _fragment = new Fragment(getDecodedSB)
    }
  }

  private def `ihier-part`: Rule0 = rule {
    ("//" ~ `iauthority` ~ `ipath-abempty`) | (run { _authority = null } ~ (`ipath-absolute` | `ipath-rootless` | `ipath-empty`))
  }

  private def `url`: Rule0 = rule {
    scheme ~ ':' ~ `ihier-part` ~ optional('?' ~ `iquery`) ~ optional('#' ~ `ifragment`) ~ EOI
  }

  private def `irelative-part`: Rule0 = rule {
    ("//" ~ `iauthority` ~ `ipath-abempty`) | `ipath-absolute` | `ipath-noscheme` | `ipath-empty`
  }

  private def `irelative-ref`: Rule0 = rule {
    `irelative-part` ~ optional('?' ~ `iquery`) ~ optional('#' ~ `ifragment`) ~ test {
      _path.nonEmpty || _authority != null || _query != null || _fragment != null
    }
  }

  private val ldh = AlphaNum ++ '-'
  private def `nid`: Rule0 = rule {
    clearSB() ~ `inid_allowed` ~ appendSBAsLower() ~ (1 to 31).times(ldh ~ appendSBAsLower()) ~ test(AlphaNum(lastChar)) ~ run {
      _nid = new Nid(sb.toString)
    }
  }

  private def `nss`: Rule0 = rule {
    `ipath-rootless`
  }

  private def `component`: Rule1[Component] = rule {
    clearSB() ~ oneOrMore(!"?+" ~ !"?=" ~ `icomponent_allowed` ~ appendSB() | `pct-encoded`) ~ push(new Component(getDecodedSB))
  }

  private def `rq-components`: Rule0 = {
    def `r-component`: Rule0 = rule {
      "?+" ~ test(_r == null) ~ `component` ~> ((c: Component) => _r = c)
    }

    def `q-component`: Rule0 = rule {
      "?=" ~ `iquery` ~ test(sb.length() > 0)
    }

    rule {
      // r component can only be matched once per parser, this allows components to be commutative
      optional(`r-component`) ~ optional(`q-component`) ~ optional(`r-component`)
    }
  }

  private def `urn`: Rule0 = rule {
    "urn:" ~ `nid` ~ ':' ~ `nss` ~ `rq-components` ~ optional('#' ~ `ifragment`) ~ EOI
  }

  private val `nc-name-start` = Alpha ++ '_' ++ List(
    0xC0   to 0xD6,   0xD8   to 0xF6,   0xF8    to 0x2FF,
    0x370  to 0x37D,  0x37F  to 0x1FFF, 0x200C  to 0x200D,
    0x2070 to 0x218F, 0x2C00 to 0x2FEF, 0x300   to 0xD7FF,
    0xF900 to 0xFDCF, 0xFDF0 to 0xFFFD, 0x10000 to 0xEFFFF
  ).map(r => CharPredicate.from(c => r contains c.toInt)).reduce(_ ++ _)

  private val `nc-name-rest` = `nc-name-start` ++ Digit ++ CharPredicate("-.", "\u00B7", '\u0300' to '\u036F', '\u203F' to '\u2040')

  private def `nc-name`: Rule0 = rule {
    clearSB() ~ `nc-name-start` ~ appendSB() ~ zeroOrMore(`nc-name-rest` ~ appendSB()) ~ run {
      _ncName = new Prefix(sb.toString)
    }
  }

  private def `curie`: Rule0 = rule {
    `nc-name` ~ ':' ~ `irelative-ref` ~ EOI
  }
}

@SuppressWarnings(Array("UnsafeTraversableMethods"))
object IriParser {

  private[iri] val `ucschar` = List(
    0xA0    to 0xD7FF,  0xF900  to 0xFDCF,  0xFDF0  to 0xFFEF,
    0x10000 to 0x1FFFD, 0x20000 to 0x2FFFD, 0x30000 to 0x3FFFD,
    0x40000 to 0x4FFFD, 0x50000 to 0x5FFFD, 0x60000 to 0x6FFFD,
    0x70000 to 0x7FFFD, 0x80000 to 0x8FFFD, 0x90000 to 0x9FFFD,
    0xA0000 to 0xAFFFD, 0xB0000 to 0xBFFFD, 0xC0000 to 0xCFFFD,
    0xD0000 to 0xDFFFD, 0xE1000 to 0xEFFFD
  ).map(r => CharPredicate.from(c => r contains c.toInt)).reduce(_ ++ _)

  private[iri] val `iprivate` = List(
    0xE000 to 0xF8FF, 0xF0000 to 0xFFFFD, 0x100000 to 0x10FFFD
  ).map(r => CharPredicate.from(c => r contains c.toInt)).reduce(_ ++ _)

  private[iri] val `sub-delims_preds` = CharPredicate("!$&'()*+,;=")
  private[iri] val `unreserved_preds` = AlphaNum ++ CharPredicate("-._~")
  private[iri] val `iunreserved_preds` = `unreserved_preds` ++ `ucschar`
  private[iri] val `ipchar_preds` = `iunreserved_preds` ++ `sub-delims_preds` ++ CharPredicate(":@")
  private[iri] val `pchar_preds` = `unreserved_preds` ++ `sub-delims_preds` ++ CharPredicate(":@")

  private[iri] val `iuser_info_allowed` = `iunreserved_preds` ++ `sub-delims_preds` ++ ':'
  private[iri] val `user_info_allowed` = `unreserved_preds` ++ `sub-delims_preds` ++ ':'

  private[iri] val `inamed_host_allowed` = `iunreserved_preds` ++ `sub-delims_preds`
  private[iri] val `named_host_allowed` = `unreserved_preds` ++ `sub-delims_preds`

  private[iri] val `iquery_allowed` = `ipchar_preds` ++ `iprivate` ++ CharPredicate("/?") -- CharPredicate("=&")
  private[iri] val `query_allowed` = `pchar_preds` ++ CharPredicate("/?") -- CharPredicate("=&")

  private[iri] val `ifragment_allowed` = `ipchar_preds`  ++ CharPredicate("/?")
  private[iri] val `fragment_allowed` = `pchar_preds`  ++ CharPredicate("/?")

  private[iri] val `inid_allowed` = AlphaNum
  private[iri] val `nid_allowed` = AlphaNum

  private[iri] val `icomponent_allowed` = `ipchar_preds`  ++ CharPredicate("/?")
  private[iri] val `component_allowed` = `pchar_preds`  ++ CharPredicate("/?")

  /**
    * A direct port of the JDKs [[java.net.URLDecoder#decode(String, Charset)]] implementation that doesn't attempt to convert
    * ''+'' into a space character '' ''. Decodes a percent encoded string using the specified charset.
    *
    * @param string  the string to decode
    * @param charset the given charset
    * @throws IllegalArgumentException if it encounters illegal characters
    * @see [[java.net.URLDecoder#decode(String, Charset)]]
    */
  @SuppressWarnings(Array("NullAssignment", "NullParameter"))
  private[iri] def decode(string: String, charset: Charset): String = {
    var needToChange = false
    val numChars = string.length
    val sb = new JStringBuilder(if (numChars > 500) numChars / 2 else numChars)
    var i = 0

    var c: Char = 0
    var bytes: Array[Byte] = null
    while (i < numChars) {
      c = string.charAt(i)
      c match {
        case '%' =>
          try {
            if (bytes == null) bytes = new Array[Byte]((numChars - i) / 3)
            var pos = 0
            while ({ ((i + 2) < numChars) && (c == '%') }) {
              val v = Integer.parseInt(string.substring(i + 1, i + 3), 16)
              if (v < 0) throw new IllegalArgumentException("URLDecoder: Illegal hex characters in escape " + "(%) pattern - negative value")
              bytes({ pos += 1; pos - 1 }) = v.toByte
              i += 3
              if (i < numChars) c = string.charAt(i)
            }
            if ((i < numChars) && (c == '%')) throw new IllegalArgumentException("URLDecoder: Incomplete trailing escape (%) pattern")
            sb.append(new String(bytes, 0, pos, charset))
          } catch {
            case e: NumberFormatException =>
              throw new IllegalArgumentException("URLDecoder: Illegal hex characters in escape (%) pattern - " + e.getMessage)
          }
          needToChange = true
        case _ =>
          sb.append(c)
          i += 1
      }
    }
    if (needToChange) sb.toString else string
  }
}