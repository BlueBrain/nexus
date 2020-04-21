package ch.epfl.bluebrain.nexus.rdf.jsonld.context

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword.context
import io.circe.Decoder.Result
import io.circe._

sealed private[context] trait UnresolvedContext extends Product with Serializable

private[context] object UnresolvedContext {
  final case class LocalContext(jObj: JsonObject)                              extends UnresolvedContext
  final case class ExternalContext(uri: Uri)                                   extends UnresolvedContext
  final case class ArrayContext(values: Seq[UnresolvedContext] = Vector.empty) extends UnresolvedContext

  private def add(ctx: ArrayContext, newCtx: UnresolvedContext): ArrayContext =
    ctx.copy(values = ctx.values :+ newCtx)

  private def err(hc: HCursor) = DecodingFailure(s"Wrong format for JsonLD $context", hc.history)

  private def fromArr(jArr: Vector[Json]): Result[UnresolvedContext] =
    jArr
      .foldM(ArrayContext()) {
        case (ctx, json) if json.isString => json.as[Uri].map(uri => add(ctx, ExternalContext(uri)))
        case (ctx, json)                  => json.asObject.toRight(err(json.hcursor)).map(jObj => add(ctx, LocalContext(jObj)))
      }
      .map {
        case ArrayContext(Seq(ctx)) => ctx
        case arr                    => arr
      }

  implicit val unresolvedContextDecoder: Decoder[UnresolvedContext] =
    Decoder.instance { hc =>
      if (hc.value.isString) hc.value.as[Uri].map(ExternalContext)
      else hc.value.arrayOrObject(Left(err(hc)), fromArr, jObj => Right(LocalContext(jObj)))
    }
}
