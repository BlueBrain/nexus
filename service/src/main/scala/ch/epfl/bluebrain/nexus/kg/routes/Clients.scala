package ch.epfl.bluebrain.nexus.kg.routes

import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient
import ch.epfl.bluebrain.nexus.commons.search.QueryResults
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import io.circe.Json
import monix.eval.Task

/**
  * Wraps the different clients
  *
  * @param sparql               the sparql indexer client
  * @param elasticSearch        the ElasticSearch indexer client
  * @param admin                the implicitly available admin client
  * @param iam                  the implicitly available iam client
  * @param defaultRemoteStorage the implicitly available storage client
  * @param http                 the implicitly available [[UntypedHttpClient]]
  * @param uclJson              the implicitly available [[HttpClient]] with a JSON unmarshaller
  * @tparam F the monadic effect type
  */
final case class Clients[F[_]]()(implicit
    val sparql: BlazegraphClient[F],
    val elasticSearch: ElasticSearchClient[F],
    val admin: AdminClient[F],
    val iam: IamClient[F],
    val defaultRemoteStorage: StorageClient[F],
    val rsSearch: HttpClient[F, QueryResults[Json]],
    val http: UntypedHttpClient[Task],
    val uclJson: HttpClient[Task, Json]
)

object Clients {
  implicit def esClient[F[_]](implicit clients: Clients[F]): ElasticSearchClient[F]  = clients.elasticSearch
  implicit def sparqlClient[F[_]](implicit clients: Clients[F]): BlazegraphClient[F] = clients.sparql
}
