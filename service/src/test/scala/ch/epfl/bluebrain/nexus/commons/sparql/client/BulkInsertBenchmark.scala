package ch.epfl.bluebrain.nexus.commons.sparql.client

import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClientFixture._
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlWriteQuery._
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.Node.blank
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax._
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.util.{EitherValues, IOValues, Randomness, Resources}
import com.bigdata.rdf.sail.webapp.NanoSparqlServer
import org.apache.commons.io.FileUtils
import org.eclipse.jetty.server.Server
import org.openjdk.jmh.annotations._

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.Try

//noinspection TypeAnnotation
/**
  * Benchmark on Blazegraph insert operations: Calculates the throughput taken for 100 graphs (or 3100 triples) to be indexed in Blazegraph.
  * Each graph consist of 31 triples
  *
  * To run it, execute on the sbt shell: ''jmh:run -i 10 -wi 10 -f1 -t1 .*BulkInsertBenchmark.*''
  * Which means "10 iterations" "10 warmup iterations" "1 fork" "1 thread"
  * Results:
  * Benchmark                     Mode  Cnt  Score   Error  Units
  * BulkInsertBenchmark.bulk1    thrpt   10  0,376 ± 0,019  ops/s
  * BulkInsertBenchmark.bulk10   thrpt   10  1,197 ± 0,129  ops/s
  * BulkInsertBenchmark.bulk20   thrpt   10  1,597 ± 0,318  ops/s
  * BulkInsertBenchmark.bulk50   thrpt   10  2,031 ± 0,204  ops/s
  * BulkInsertBenchmark.bulk100  thrpt   10  1,769 ± 0,496  ops/s
  */
@State(Scope.Thread)
class BulkInsertBenchmark extends IOValues with Resources with Randomness with EitherValues {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(10.seconds, 2.milliseconds)

  var dataList: Seq[Graph]         = Seq.empty
  var client: BlazegraphClient[IO] = _

  def graphUri: Uri = s"http://nexus.example.com/graphs/${genString(length = 5)}"

  private implicit var system: ActorSystem = _
  private var server: Server               = _

  @Setup(Level.Trial) def doSetup(): Unit = {

    val port = freePort()
    system = ActorSystem(s"BulkInsertBenchmark")
    implicit val ec: ExecutionContextExecutor      = system.dispatcher
    implicit val uc: UntypedHttpClient[IO]         = untyped[IO]
    implicit val jc: HttpClient[IO, SparqlResults] = withUnmarshaller[IO, SparqlResults]
    implicit val timer: Timer[IO]                  = IO.timer(ec)
    implicit val retryConfig: RetryStrategyConfig  = RetryStrategyConfig("never", 0.millis, 0.millis, 0, 0.millis)
    val _                                          = Try(FileUtils.forceDelete(new File("bigdata.jnl")))

    server = {
      System.setProperty("jetty.home", getClass.getResource("/war").toExternalForm)
      NanoSparqlServer.newInstance(port, null, null)
    }

    server.start()
    client = BlazegraphClient(s"http://$localhost:$port/blazegraph", "namespace", None)
    val json = jsonContentOf("/resource.json")
    client.createNamespace(properties()).ioValue

    dataList = List.fill(100)(json.toGraph(blank).rightValue)
  }

  @TearDown(Level.Trial) def doTearDown(): Unit = {
    system.terminate()
    system.whenTerminated.futureValue
    server.stop()
    val _ = Try(FileUtils.forceDelete(new File("bigdata.jnl")))
  }

  @Benchmark
  def bulk1(): Unit =
    dataList.foreach { data =>
      client.bulk(Seq(replace(graphUri, data))).ioValue
    }
  @Benchmark
  def bulk10(): Unit = {
    val iter = dataList.iterator
    (0 until 10).foreach { _ =>
      val bulked = Seq.fill(10)(iter.next()).map(data => replace(graphUri, data))
      client.bulk(bulked).ioValue
    }
  }

  @Benchmark
  def bulk20(): Unit = {
    val iter = dataList.iterator
    (0 until 5).foreach { _ =>
      val bulked = Seq.fill(20)(iter.next()).map(data => replace(graphUri, data))
      client.bulk(bulked).ioValue
    }
  }

  @Benchmark
  def bulk50(): Unit = {
    val iter = dataList.iterator
    (0 until 2).foreach { _ =>
      val bulked = Seq.fill(50)(iter.next()).map(data => replace(graphUri, data))
      client.bulk(bulked).ioValue
    }
  }

  @Benchmark
  def bulk100(): Unit = {
    val bulked: Seq[SparqlWriteQuery] = dataList.map(data => replace(graphUri, data))
    val _                             = client.bulk(bulked).ioValue
  }
}
