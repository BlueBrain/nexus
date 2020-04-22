package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry.NodeObjectArray
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.{IdMap, SetValue}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.jsonld.{keyword, NodeObject}
import io.circe.{Json, JsonObject}

private class IdMapParser private (obj: JsonObject, override val cursor: TermDefinitionCursor) extends JsonLDParser {

  final def parse(): Either[ParsingStatus, IdMap] =
    obj.toList.foldM(IdMap()) {
      case (acc, (_, json)) if json.isNull => Right(acc)

      case (IdMap(accMap, accNone), (term, json)) if isAlias(term, keyword.none) =>
        ArrayObjectParser
          .set(json, cursor)
          .flatMap(collectNodeObjectOrFail(_, None))
          .map(nodes => IdMap(accMap, accNone ++ nodes))

      case (IdMap(accMap, accNone), (idTerm, json)) =>
        for {
          id    <- expandId(idTerm)
          nodes <- ArrayObjectParser.set(json, cursor).flatMap(collectNodeObjectOrFail(_, Some(id)))
        } yield IdMap(accMap.updatedWith(id)(v => Some(v.fold(nodes)(_ ++ nodes))), accNone)
    }

  private def collectNodeObjectOrFail(set: SetValue, id: Option[Uri]): Either[InvalidObjectFormat, Seq[NodeObject]] =
    set.value.toList.foldM(Vector.empty[NodeObject]) {
      case (acc, NodeObjectArray(n)) if n.id.nonEmpty || id.isEmpty => Right(acc :+ n)
      case (acc, NodeObjectArray(n))                                => Right(acc :+ n.copy(id = id))
      case _                                                        => Left(invalidIdValue)
    }
}

private[jsonld] object IdMapParser {

  def apply(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, IdMap] =
    if (json.isNull)
      Left(NullObject)
    else if (cursor.value.exists(_.container.contains(id)))
      json.asObject.toRight(NotMatchObject).flatMap(apply(_, cursor))
    else
      Left(NotMatchObject)

  def apply(obj: JsonObject, cursor: TermDefinitionCursor): Either[ParsingStatus, IdMap] =
    new IdMapParser(obj, cursor).parse()
}
