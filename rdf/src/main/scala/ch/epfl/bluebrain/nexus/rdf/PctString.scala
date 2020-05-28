package ch.epfl.bluebrain.nexus.rdf

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

import org.parboiled2.CharPredicate

object PctString {

  private val UTF8 = UTF_8.displayName()
  private[rdf] def pctEncodedIgnore(s: String, toIgnore: CharPredicate): String = {
    val (_, encoded) = s.foldLeft((0, new StringBuilder())) {
      case ((forceIgnore, sb), b) if forceIgnore > 0       => (forceIgnore - 1, sb.append(b))
      case ((_, sb), b) if toIgnore.matchesAny(b.toString) => (0, sb.append(b))
      case ((_, sb), b) if b == '%'                        => (2, sb.append(b))
      case ((_, sb), b)                                    => (0, pctEncode(sb, b))
    }
    encoded.toString()
  }

  private def pctEncode(sb: StringBuilder, char: Char): StringBuilder =
    if (char == ' ') sb.append("%20")
    else sb.append(URLEncoder.encode(char.toString, UTF8))

  implicit final class StringEncodingOpts(private val value: String) extends AnyVal {
    def pctEncodeIgnore(toIgnore: CharPredicate): String = pctEncodedIgnore(value, toIgnore)
  }
}
