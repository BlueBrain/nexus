package ch.epfl.bluebrain.nexus

import java.net.URLEncoder
import java.util.UUID

import akka.persistence.query.Offset
import cats.Show
import cats.implicits._
import ch.epfl.bluebrain.nexus.iam.client.types.Identity
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.indexing.Statistics.ViewStatistics
import ch.epfl.bluebrain.nexus.kg.indexing.IdentifiedProgress
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.InvalidResourceFormat
import ch.epfl.bluebrain.nexus.kg.resources.{Rejection, ResId}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.BNode
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.{Cursor, DecodingError, Graph, GraphDecoder, GraphEncoder}

import scala.util.Try

package object kg {

  type IdStats  = IdentifiedProgress[ViewStatistics]
  type IdOffset = IdentifiedProgress[Offset]

  /**
    * @param a the value to encode
    * @return attempts to url encode the provided ''a''. It returns the provided ''s'' when encoding fails
    */
  def urlEncode[A: Show](a: A): String =
    urlEncode(a.show)

  /**
    * @param s the value to encode
    * @return attempts to url encode the provided ''s''. It returns the provided ''s'' when encoding fails
    */
  def urlEncode(s: String): String =
    urlEncodeOrElse(s)(s)

  /**
    * @param s       the value to encode
    * @param default the value when encoding ''s'' fails
    * @return attempts to url encode the provided ''s''. It returns the provided ''default'' when encoding fails
    */
  def urlEncodeOrElse(s: String)(default: => String): String =
    Try(URLEncoder.encode(s, "UTF-8").replaceAll("\\+", "%20")).getOrElse(default)

  def uuid(): String =
    UUID.randomUUID().toString.toLowerCase

  def identities(resId: ResId, cursors: Set[Cursor]): Either[Rejection, Set[Identity]] = {
    import alleycats.std.set._
    cursors.foldM(Set.empty[Identity]) { (acc, cursor) =>
      identity(resId, cursor).map(i => acc + i)
    }
  }

  def anonDecoder: GraphDecoder[Anonymous] =
    GraphDecoder.instance { c =>
      val tpe = c.down(rdf.tpe)
      tpe.as[AbsoluteIri].flatMap {
        case nxv.Anonymous.value => Right(Anonymous)
        case other               =>
          val msg = s"Type does not match expected '${nxv.Anonymous.value.asUri}', but '${other.asUri}'"
          Left(DecodingError(msg, tpe.history))
      }
    }

  def userDecoder: GraphDecoder[User] =
    GraphDecoder.instance { c =>
      (c.down(nxv.subject.value).as[String], c.down(nxv.realm.value).as[String]).mapN(User.apply)
    }

  def groupDecoder: GraphDecoder[Group] =
    GraphDecoder.instance { c =>
      (c.down(nxv.group.value).as[String], c.down(nxv.realm.value).as[String]).mapN(Group.apply)
    }

  def authenticatedDecoder: GraphDecoder[Authenticated] =
    GraphDecoder.instance { c =>
      c.down(nxv.realm).as[String].map(realm => Authenticated(realm))
    }

  implicit final def identityDecoder: GraphDecoder[Identity] =
    anonDecoder.asInstanceOf[GraphDecoder[Identity]] or
      userDecoder.asInstanceOf[GraphDecoder[Identity]] or
      groupDecoder.asInstanceOf[GraphDecoder[Identity]] or
      authenticatedDecoder.asInstanceOf[GraphDecoder[Identity]]

  def identity(resId: ResId, c: Cursor): Either[Rejection, Identity] = {
    identityDecoder(c).leftMap { _ =>
      InvalidResourceFormat(
        resId.ref,
        "The provided payload could not be mapped to a resolver because the identity format is wrong"
      )
    }
  }

  implicit final val identityEncoder: GraphEncoder[Identity] = GraphEncoder.instance {
    case Anonymous            =>
      Graph(BNode())
        .append(rdf.tpe, nxv.Anonymous)
    case User(subject, realm) =>
      Graph(BNode())
        .append(rdf.tpe, nxv.User)
        .append(nxv.subject, subject)
        .append(nxv.realm, realm)
    case Group(group, realm)  =>
      Graph(BNode())
        .append(rdf.tpe, nxv.Group)
        .append(nxv.group, group)
        .append(nxv.realm, realm)
    case Authenticated(realm) =>
      Graph(BNode())
        .append(rdf.tpe, nxv.Authenticated)
        .append(nxv.realm, realm)
  }
}
