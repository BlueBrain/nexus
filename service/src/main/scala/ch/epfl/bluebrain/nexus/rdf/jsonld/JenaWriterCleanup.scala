package ch.epfl.bluebrain.nexus.rdf.jsonld

import java.util.UUID

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.jena.Jena
import ch.epfl.bluebrain.nexus.rdf.jsonld.JenaWriterCleanup._
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.JsonLdSyntax
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.{Graph, Iri}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.apache.jena.rdf.model.ModelFactory

import scala.util.Try

private[jsonld] final class JenaWriterCleanup(ctx: Json) extends JsonLdSyntax {

  private lazy val m = Jena.parse(ctx.noSpaces).getOrElse(ModelFactory.createDefaultModel())

  /**
    * Removes the "@graph" from the json document if there is only
    * one element inside the array object containing the @graph key
    *
    * @param json the json to be cleaned
    */
  def removeSingleGraph(json: Json): Json =
    (json.hcursor.downField("@graph").focus.flatMap(_.asArray).flatMap {
      case head +: IndexedSeq() => Some(head)
      case _                    => None
    }, json.asObject) match {
      case (Some(entity), Some(obj)) => entity deepMerge obj.remove("@graph").asJson
      case _                         => json
    }

  /**
    * Work around for Jena to replace the keys which are in a context with @type
    * and using the UseNativeTypes flag in the JsonLdOptions
    */
  def cleanFromCtx: Json = {

    def inner(ctx: Json): Json =
      ctx.arrayOrObject(ctx, arr => Json.fromValues(arr.map(inner)), obj => deleteType(obj).asJson)

    def deleteType(jObj: JsonObject): JsonObject =
      JsonObject.fromIterable(
        jObj.toVector
          .filter {
            case ("@type", j) => j.asString.forall(s => !knownTypes.contains(m.expandPrefix(s)))
            case _            => true
          }
          .map { case (k, v) => k -> inner(v) }
      )

    inner(ctx)
  }

  /**
    * Work around for Jena to replace the expanded generated objects into the compacted form
    * For example, the following json payload:
    * {{{
    * "age": {
    *    "@type": "xsd:integer",
    *    "@value": "2"
    * }
    * }}}
    * will be converted to: "age": 2
    *
    * It also deals with relative Iris caused @base, expanding them when required
    *
    * @param json the json to be cleaned
    */
  def cleanFromJson(json: Json, g: Graph): Json = {
    val stringValues = g.triples.collect { case (_, _, lt: Literal) => lt.lexicalForm }
    val maybeBase    = json.contextValue.hcursor.get[String]("@base").flatMap(Iri.absolute)
    val typeAliases  = JsonLd.contextAliases(json, "@type") + "@type"

    def inner(ctx: Json): Json =
      ctx.arrayOrObject(ctx, arr => Json.fromValues(arr.map(inner)), obj => deleteType(obj))

    def tryBoolean(j: Json) =
      if (j.isBoolean) Some(j) else j.asString.flatMap(s => Try(s.toBoolean).toOption).map(Json.fromBoolean)

    def tryInt(j: Json) =
      j.asNumber.flatMap(_.toInt).orElse(j.asString.flatMap(s => Try(s.toInt).toOption)).map(Json.fromInt)

    def tryLong(j: Json) =
      j.asNumber.flatMap(_.toLong).orElse(j.asString.flatMap(s => Try(s.toLong).toOption)).map(Json.fromLong)

    def tryDouble(j: Json) =
      j.asNumber.map(_.toDouble).orElse(j.asString.flatMap(s => Try(s.toDouble).toOption)).flatMap(Json.fromDouble)

    def tryFloat(j: Json) =
      j.asNumber
        .map(_.toDouble)
        .map(_.toFloat)
        .orElse(j.asString.flatMap(s => Try(s.toFloat).toOption))
        .flatMap(Json.fromFloat)

    def recursiveFollow(jObj: JsonObject): Json =
      JsonObject
        .fromIterable(jObj.toVector.map {
          case (k, v) if v.isString => k -> jsonAsStringAttemptExpand(v)
          case (k, v) if v.isArray  => k -> v.asArray.map(_.map(jsonAsStringAttemptExpand)).asJson
          case (k, v)               => k -> inner(v)
        })
        .asJson

    def jsonAsStringAttemptExpand(v: Json): Json =
      v.asString
        .flatMap {
          case s if s.startsWith("../") && !stringValues.contains(s) => expandWithBase(s).toOption.map(Json.fromString)
          case _                                                     => None
        }
        .getOrElse(inner(v))

    def expandWithBase(s: String) =
      (maybeBase -> Iri.relative(s.substring(3))).mapN { (base, relative) =>
        relative.resolve(base).asString
      }

    def deleteType(jObj: JsonObject): Json =
      typeAliases.find(tpe => jObj.contains(tpe) && jObj.contains("@value") && jObj.size == 2) match {
        case Some(typeAlias) =>
          (jObj(typeAlias).flatMap(_.asString).map(m.expandPrefix), jObj("@value"))
            .mapN {
              case (t, value) if t == xsd.boolean.toString  => tryBoolean(value)
              case (t, value) if t == xsd.int.toString      => tryInt(value)
              case (t, value) if t == xsd.integer.toString  => tryLong(value)
              case (t, value) if t == xsd.long.toString     => tryLong(value)
              case (t, value) if t == xsd.float.toString    => tryFloat(value)
              case (t, value) if t == xsd.decimal.toString  => tryDouble(value)
              case (t, value) if t == xsd.double.toString   => tryDouble(value)
              case (t, value) if t == xsd.dateTime.toString => value.asString.map(Json.fromString)
              case (t, value) if t == xsd.string.toString   => value.asString.map(Json.fromString)
              case _                                        => None
            }
            .flatten
            .getOrElse(recursiveFollow(jObj))
        case _ =>
          recursiveFollow(jObj)

      }

    inner(json)
  }
}

private[jsonld] object JenaWriterCleanup {
  val knownTypes: Set[String] =
    Set(
      xsd.boolean.toString,
      xsd.int.toString,
      xsd.integer.toString,
      xsd.string.toString,
      xsd.decimal.toString,
      xsd.double.toString,
      xsd.float.toString,
      xsd.long.toString,
      xsd.string.toString,
      xsd.dateTime.toString
    )

  final val reservedId = url"http://dummy.com/${UUID.randomUUID()}"
}
