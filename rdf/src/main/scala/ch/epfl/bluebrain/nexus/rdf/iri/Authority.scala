package ch.epfl.bluebrain.nexus.rdf.iri

import cats.implicits._
import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.PctString._
import ch.epfl.bluebrain.nexus.rdf.iri.Authority.Host._
import ch.epfl.bluebrain.nexus.rdf.iri.Authority._
import ch.epfl.bluebrain.nexus.rdf.iri.IriParser._
import io.circe.{Decoder, Encoder}

import scala.collection.immutable.ArraySeq
import scala.collection.{immutable, View}

/**
  * The Authority part of an IRI as defined by RFC 3987.
  *
  * @param userInfo the optional user info part
  * @param host     the host part
  * @param port     the optional port part
  */
final case class Authority(userInfo: Option[UserInfo], host: Host, port: Option[Port]) {

  /**
    * @return the string representation for the Authority segment compatible with the rfc3987
    *         (using percent-encoding only for delimiters)
    */
  lazy val iriString: String = {
    val ui = userInfo.map(_.iriString + "@").getOrElse("")
    val p  = port.map(":" + _.value.toString).getOrElse("")
    s"$ui${host.iriString}$p"
  }

  /**
    * @return the string representation using percent-encoding for the Authority segment
    *         when necessary according to rfc3986
    */
  lazy val uriString: String = {
    {
      val ui = userInfo.map(_.uriString + "@").getOrElse("")
      val p  = port.map(":" + _.value.toString).getOrElse("")
      s"$ui${host.uriString}$p"
    }
  }
}

object Authority {

  /**
    * A user info representation as specified by RFC 3987.
    *
    * @param value the underlying string representation
    */
  final case class UserInfo private[iri] (value: String) {

    /**
      * As per the specification the user info is case sensitive.  This method allows comparing two user info values
      * disregarding the character casing.
      *
      * @param that the user info to compare to
      * @return true if the underlying values are equal (diregarding their case), false otherwise
      */
    def equalsIgnoreCase(that: UserInfo): Boolean =
      this.value equalsIgnoreCase that.value

    /**
      * @return the string representation for the Userinfo segment compatible with the rfc3987
      *         (using percent-encoding only for delimiters)
      */
    lazy val iriString: String = value.pctEncodeIgnore(`iuser_info_allowed`)

    /**
      * @return the string representation using percent-encoding for the Userinfo segment
      *         when necessary according to rfc3986
      */
    lazy val uriString: String = value.pctEncodeIgnore(`user_info_allowed`)
  }

  object UserInfo {

    /**
      * Attempt to construct a new UserInfo from the argument validating the character encodings as per RFC 3987.
      *
      * @param string the string to parse as a user info.
      * @return Right(UserInfo(value)) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, UserInfo] =
      new IriParser(string).parseUserInfo

    implicit final val userInfoShow: Show[UserInfo]       = Show.show(_.iriString)
    implicit final val userInfoEq: Eq[UserInfo]           = Eq.fromUniversalEquals
    implicit final val userInfoEncoder: Encoder[UserInfo] = Encoder.encodeString.contramap(_.iriString)
    implicit final val userInfoDecoder: Decoder[UserInfo] = Decoder.decodeString.emap(UserInfo.apply)
  }

  /**
    * Host part of an Iri as defined in RFC 3987.
    */
  sealed abstract class Host extends Product with Serializable {

    /**
      * @return true if the host is an IPv4 address, false otherwise
      */
    def isIPv4: Boolean = false

    /**
      * @return true if the host is an IPv6 address, false otherwise
      */
    def isIPv6: Boolean = false

    /**
      * @return true if the host is a named host, false otherwise
      */
    def isNamed: Boolean = false

    /**
      * @return Some(this) if this is an IPv4Host, None otherwise
      */
    def asIPv4: Option[IPv4Host] = None

    /**
      * @return Some(this) if this is an IPv6Host, None otherwise
      */
    def asIPv6: Option[IPv6Host] = None

    /**
      * @return Some(this) if this is a NamedHost, None otherwise
      */
    def asNamed: Option[NamedHost] = None

    def value: String

    /**
      * @return the string representation using percent-encoding for the Host segment
      *         when necessary according to rfc3986
      */
    def uriString: String

    /**
      * @return the string representation for the Host segment compatible with the rfc3987
      *         (using percent-encoding only for delimiters)
      */
    def iriString: String
  }

  object Host {

    /**
      * Constructs a new IPv4Host from the argument bytes.
      *
      * @param byte1 the first byte of the address
      * @param byte2 the second byte of the address
      * @param byte3 the third byte of the address
      * @param byte4 the fourth byte of the address
      * @return the IPv4Host represented by these bytes
      */
    final def ipv4(byte1: Byte, byte2: Byte, byte3: Byte, byte4: Byte): IPv4Host =
      IPv4Host(byte1, byte2, byte3, byte4)

    /**
      * Attempt to construct a new IPv4Host from its 32bit representation.
      *
      * @param bytes a 32bit IPv4 address
      * @return Right(IPv4Host(bytes)) if the bytes is a 4byte array, Left(error) otherwise
      */
    final def ipv4(bytes: Array[Byte]): Either[String, IPv4Host] =
      IPv4Host(bytes)

    /**
      * Attempt to construct a new IPv4Host from its string representation as specified by RFC 3987.
      *
      * @param string the string to parse as an IPv4 address.
      * @return Right(IPv4Host(bytes)) if the string conforms to specification, Left(error) otherwise
      */
    final def ipv4(string: String): Either[String, IPv4Host] =
      IPv4Host(string)

    /**
      * Attempt to construct a new IPv6Host from its 128bit representation.
      *
      * @param bytes a 128bit IPv6 address
      * @return Right(IPv6Host(bytes)) if the bytes is a 16byte array, Left(error) otherwise
      */
    def ipv6(bytes: Array[Byte]): Either[String, IPv6Host] =
      IPv6Host(bytes)

    /**
      * Attempt to construct a new NamedHost from the argument validating the character encodings as per RFC 3987.
      *
      * @param string the string to parse as a named host.
      * @return Right(NamedHost(value)) if the string conforms to specification, Left(error) otherwise
      */
    def named(string: String): Either[String, NamedHost] =
      NamedHost(string)

    /**
      * An IPv4 host representation as specified by RFC 3987.
      *
      * @param bytes the underlying bytes
      */
    final case class IPv4Host private[iri] (bytes: immutable.Seq[Byte]) extends Host {
      override def isIPv4: Boolean          = true
      override def asIPv4: Option[IPv4Host] = Some(this)
      override lazy val value: String       = bytes.map(_ & 0xFF).mkString(".")
      override lazy val uriString: String   = value
      override lazy val iriString: String   = value
    }

    object IPv4Host {

      /**
        * Attempt to construct a new IPv4Host from its 32bit representation.
        *
        * @param bytes a 32bit IPv4 address
        * @return Right(IPv4Host(bytes)) if the bytes is a 4byte array, Left(error) otherwise
        */
      final def apply(bytes: Array[Byte]): Either[String, IPv4Host] =
        Either
          .catchNonFatal(fromBytes(bytes))
          .leftMap(_ => "Illegal IPv4Host byte representation")

      /**
        * Attempt to construct a new IPv4Host from its string representation as specified by RFC 3987.
        *
        * @param string the string to parse as an IPv4 address.
        * @return Right(IPv4Host(bytes)) if the string conforms to specification, Left(error) otherwise
        */
      final def apply(string: String): Either[String, IPv4Host] =
        new IriParser(string).parseIPv4

      /**
        * Constructs a new IPv4Host from the argument bytes.
        *
        * @param byte1 the first byte of the address
        * @param byte2 the second byte of the address
        * @param byte3 the third byte of the address
        * @param byte4 the fourth byte of the address
        * @return the IPv4Host represented by these bytes
        */
      final def apply(byte1: Byte, byte2: Byte, byte3: Byte, byte4: Byte): IPv4Host =
        new IPv4Host(ArraySeq.unsafeWrapArray(Array(byte1, byte2, byte3, byte4)))

      private def fromBytes(bytes: Array[Byte]): IPv4Host = {
        require(bytes.length == 4)
        new IPv4Host(bytes.toIndexedSeq)
      }

      implicit final val ipv4HostShow: Show[IPv4Host] =
        Show.show(_.iriString)

      implicit final val ipv4HostEq: Eq[IPv4Host] =
        Eq.fromUniversalEquals

      implicit final val ipv4HostEncoder: Encoder[IPv4Host] = Encoder.encodeString.contramap(_.iriString)
      implicit final val ipv4HostDecoder: Decoder[IPv4Host] = Decoder.decodeString.emap(IPv4Host.apply)
    }

    /**
      * An IPv6 host representation as specified by RFC 3987.
      *
      * @param bytes the underlying bytes
      */
    final case class IPv6Host private[iri] (bytes: immutable.Seq[Byte]) extends Host {
      override def isIPv6: Boolean          = true
      override def asIPv6: Option[IPv6Host] = Some(this)
      override lazy val uriString: String   = value
      override lazy val iriString: String   = value

      override lazy val value: String =
        bytesToString(bytes.view)

      lazy val asMixedString: String =
        bytesToString(bytes.view.slice(0, 12)) + ":" + bytes.view.slice(12, 16).map(_ & 0xFF).mkString(".")

      private def bytesToString(bytes: View[Byte]): String =
        bytes.grouped(2).map(two => Integer.toHexString(BigInt(two.toArray).intValue)).mkString(":")
    }

    object IPv6Host {

      /**
        * Attempt to construct a new IPv6Host from its 128bit representation.
        *
        * @param bytes a 128bit IPv6 address
        * @return Right(IPv6Host(bytes)) if the bytes is a 16byte array, Left(error) otherwise
        */
      final def apply(bytes: Array[Byte]): Either[String, IPv6Host] =
        Either
          .catchNonFatal(fromBytes(bytes))
          .leftMap(_ => "Illegal IPv6Host byte representation")

      private def fromBytes(bytes: Array[Byte]): IPv6Host = {
        require(bytes.length == 16)
        new IPv6Host(bytes.toIndexedSeq)
      }

      implicit final val ipv6HostShow: Show[IPv6Host] =
        Show.show(_.iriString)

      implicit final val ipv6HostEq: Eq[IPv6Host] =
        Eq.fromUniversalEquals

      implicit final val ipv6HostEncoder: Encoder[IPv6Host] = Encoder.encodeString.contramap(_.iriString)
      // TODO: Missing ipv6 parser
      //final implicit val ipv6HostDecoder: Decoder[IPv6Host] = Decoder.decodeString.emap(IPv6Host.apply)
    }

    /**
      * A named host representation as specified by RFC 3987.
      *
      * @param value the underlying string representation
      */
    final case class NamedHost private[iri] (value: String) extends Host {
      override def isNamed: Boolean           = true
      override def asNamed: Option[NamedHost] = Some(this)
      override lazy val iriString: String     = value.pctEncodeIgnore(`inamed_host_allowed`)
      override lazy val uriString: String     = value.pctEncodeIgnore(`named_host_allowed`)
    }

    object NamedHost {

      /**
        * Attempt to construct a new NamedHost from the argument validating the character encodings as per RFC 3987.
        *
        * @param string the string to parse as a named host.
        * @return Right(NamedHost(value)) if the string conforms to specification, Left(error) otherwise
        */
      final def apply(string: String): Either[String, NamedHost] =
        new IriParser(string).parseNamed

      implicit final val namedHostShow: Show[NamedHost] =
        Show.show(_.iriString)

      implicit final val namedHostEq: Eq[NamedHost] =
        Eq.fromUniversalEquals

      implicit final val namedHostEncoder: Encoder[NamedHost] = Encoder.encodeString.contramap(_.iriString)
      implicit final val namedHostDecoder: Decoder[NamedHost] = Decoder.decodeString.emap(NamedHost.apply)
    }

    implicit final val hostShow: Show[Host]       = Show.show(_.iriString)
    implicit final val hostEq: Eq[Host]           = Eq.fromUniversalEquals
    implicit final val hostEncoder: Encoder[Host] = Encoder.encodeString.contramap(_.iriString)
    implicit final def hostDecoder(named: Decoder[NamedHost], ipv4: Decoder[IPv4Host]): Decoder[Host] =
      named.map(identity[Host]) or ipv4.map(identity[Host])
  }

  /**
    * Port part of an Iri as defined by RFC 3987.
    *
    * @param value the underlying int value
    */
  final case class Port private[iri] (value: Int)

  object Port {

    /**
      * Attempts to construct a port from the argument int value.  Valid values are [0, 65535].
      *
      * @param value the underlying port value
      * @return Right(port) if successful, Left(error) otherwise
      */
    final def apply(value: Int): Either[String, Port] =
      if (value >= 0 && value < 65536) Right(new Port(value))
      else Left("Port value must in range [0, 65535]")

    /**
      * Attempts to construct a port from the argument string value.  Valid values are [0, 65535] without leading zeroes.
      *
      * @param string the string representation of the port
      * @return Right(port) if successful, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Port] =
      new IriParser(string).parsePort

    implicit final val portShow: Show[Port]       = Show.show(_.value.toString)
    implicit final val portEq: Eq[Port]           = Eq.fromUniversalEquals
    implicit final val portEncoder: Encoder[Port] = Encoder.encodeInt.contramap(_.value)
    implicit final val portDecoder: Decoder[Port] = Decoder.decodeInt.emap(Port.apply)
  }

  /**
    * Attempt to construct a new Authority from the argument validating the character encodings as per RFC 3987.
    *
    * @param string the string to parse as a named host.
    * @return Right(Authority(value)) if the string conforms to specification, Left(error) otherwise
    */
  final def apply(string: String): Either[String, Authority] =
    new IriParser(string).parseAuthority

  implicit final val authorityShow: Show[Authority] = Show.show(_.iriString)

  implicit final val authorityEq: Eq[Authority] = Eq.fromUniversalEquals

  implicit final val authorityEncoder: Encoder[Authority] = Encoder.encodeString.contramap(_.iriString)
  implicit final val authorityDecoder: Decoder[Authority] = Decoder.decodeString.emap(Authority.apply)

}
