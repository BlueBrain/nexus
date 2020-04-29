package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus.{invalidIdTerm, invalidJsonValue, invalidTerm}
import io.circe.Json

private[parser] trait JsonLDParser {

  def cursor: TermDefinitionCursor

  def expandKey(term: String, c: TermDefinitionCursor = cursor): Either[ParsingStatus, Uri] =
    c.context.toOption.fold(Uri(term))(ctx => ctx.expand(term)).leftMap(_ => invalidTerm(term))

  def expand(term: String, c: TermDefinitionCursor = cursor): Either[ParsingStatus, Uri] =
    c.context.toOption.fold(Uri(term))(ctx => ctx.expandTermValue(term)).leftMap(_ => invalidTerm(term))

  def expandId(term: String, c: TermDefinitionCursor = cursor): Either[ParsingStatus, Uri] =
    c.context.toOption.fold(Uri(term))(ctx => ctx.expandId(term)).leftMap(_ => invalidIdTerm(term))

  def isAlias(term: String, keyword: String): Boolean =
    term == keyword || cursor.context.exists(_.isAlias(term, keyword))

  def asString(json: Json, keyword: String): Either[ParsingStatus, String] =
    json.asString.toRight(invalidJsonValue(keyword))

  def keyAliases(key: String): Set[String] =
    cursor.context.toOption.flatMap(_.keywords.get(key)).fold(Set(key))(_ + key)

}
