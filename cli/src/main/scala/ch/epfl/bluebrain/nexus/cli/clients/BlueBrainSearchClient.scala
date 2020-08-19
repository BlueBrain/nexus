package ch.epfl.bluebrain.nexus.cli.clients

import cats.implicits._
import cats.effect.{Sync, Timer}
import ch.epfl.bluebrain.nexus.cli._
import ch.epfl.bluebrain.nexus.cli.clients.BlueBrainSearchClient.Embedding
import ch.epfl.bluebrain.nexus.cli.config.literature.BlueBrainSearchConfig
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, EnvConfig}
import io.circe.{Decoder, Json}
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.{Method, Request}

trait BlueBrainSearchClient[F[_]] {

  /**
    * Retrieves the vector embeddings for the passed ''text''.
    */
  def embedding(modelType: String, text: String): F[CliErrOr[Embedding]]

}

object BlueBrainSearchClient {

  final case class Embedding(value: Seq[Double])

  object Embedding {
    implicit val embeddingDecoder: Decoder[Embedding] =
      Decoder.instance(hc => hc.get[Seq[Double]]("embedding").map(Embedding(_)))
  }

  private class LiveBlueBrainSearchClient[F[_]: Timer: Console: Sync](
      client: Client[F],
      config: BlueBrainSearchConfig,
      env: EnvConfig
  ) extends AbstractHttpClient(client, env)
      with BlueBrainSearchClient[F] {

    override def embedding(modelType: String, text: String): F[CliErrOr[Embedding]] = {
      val payload = Json.obj("model" -> modelType.asJson, "text" -> text.asJson)
      val req     = Request[F](Method.POST, config.endpoint / "embed" / "json").withEntity(payload)
      executeParse[Embedding](req).map(identity)
    }
  }

  /**
    * Construct an instance of [[BlueBrainSearchClient]].
    *
    * @param console [[Console]] for logging
    * @param client  the underlying HTTP client
    * @param config  BlueBrainSearch config
    */
  final def apply[F[_]: Sync: Timer](
      client: Client[F],
      config: AppConfig,
      console: Console[F]
  ): BlueBrainSearchClient[F] = {
    implicit val c: Console[F] = console
    new LiveBlueBrainSearchClient[F](client, config.literature.blueBrainSearch, config.env)
  }
}
