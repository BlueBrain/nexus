package ch.epfl.bluebrain.nexus.tests

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpCharsets.`UTF-8`
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpRequest, MediaRange, MediaType}
import akka.stream.Materializer
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestHelpers}
import io.circe.optics.JsonPath.root
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers

class BlazegraphDsl(implicit as: ActorSystem, materializer: Materializer)
    extends TestHelpers
    with CirceLiteral
    with CirceUnmarshalling
    with Matchers {

  private val blazegraphUrl    = s"http://${sys.props.getOrElse("blazegraph-url", "localhost:9999")}"
  private val blazegraphClient = HttpClient(blazegraphUrl)

  private val `application/sparql-results+json`: MediaType.WithFixedCharset =
    MediaType.applicationWithFixedCharset("sparql-results+json", `UTF-8`, "json")

  private val sparqlJsonRange = MediaRange.One(`application/sparql-results+json`, 1f)

  private def filterNamespaces =
    root.predicate.value.string.exist(_ == "http://www.bigdata.com/rdf#/features/KB/Namespace")

  def allNamespaces: Task[List[String]] = {
    blazegraphClient(
      HttpRequest(
        method = GET,
        uri = s"$blazegraphUrl/blazegraph/namespace?describe-each-named-graph=false"
      ).addHeader(Accept(sparqlJsonRange))
    ).flatMap { res =>
      Task
        .deferFuture {
          jsonUnmarshaller(res.entity)(global, materializer)
        }
        .map { json =>
          root.results.bindings.each.filter(filterNamespaces).`object`.value.string.getAll(json)
        }
    }
  }

}
