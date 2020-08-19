package ch.epfl.bluebrain.nexus.cli.dummies

import cats.Applicative
import ch.epfl.bluebrain.nexus.cli.CliErrOr
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.clients.BlueBrainSearchClient
import ch.epfl.bluebrain.nexus.cli.clients.BlueBrainSearchClient.Embedding
import org.http4s.Status

class TestBlueBrainSearchClient[F[_]](textEmbeddings: Map[String, Seq[Double]])(implicit F: Applicative[F])
    extends BlueBrainSearchClient[F] {
  override def embedding(modelType: String, text: String): F[CliErrOr[Embedding]] =
    textEmbeddings.get(text) match {
      case Some(vector) => F.pure(Right(Embedding(vector)))
      case None         => F.pure(Left(ClientError.unsafe(Status.NotFound, "not found")))
    }
}
