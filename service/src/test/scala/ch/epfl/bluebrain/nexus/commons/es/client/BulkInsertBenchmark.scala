package ch.epfl.bluebrain.nexus.commons.es.client

import akka.actor.ActorSystem
import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient.BulkOp
import ch.epfl.bluebrain.nexus.commons.es.server.embed.ElasticServer
import ch.epfl.bluebrain.nexus.commons.http.HttpClient._
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.util.{IOValues, Randomness, Resources}
import io.circe.Json
import org.openjdk.jmh.annotations._

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

//noinspection TypeAnnotation
/**
  * Benchmark on ElasticSearch insert operations: Calculates the throughput taken for 200 documents to be indexed in ElasticSearch
  *
  * To run it, execute on the sbt shell: ''jmh:run -i 10 -wi 10 -f1 -t1 .*BulkInsertBenchmark.*''
  * Which means "10 iterations" "10 warmup iterations" "1 fork" "1 thread"
  * Results:
  * Benchmark                     Mode  Cnt  Score   Error  Units
  * BulkInsertBenchmark.bulk1    thrpt   10  0,358 ± 0,017  ops/s
  * BulkInsertBenchmark.bulk10   thrpt   10  1,212 ± 0,069  ops/s
  * BulkInsertBenchmark.bulk20   thrpt   10  1,781 ± 0,068  ops/s
  * BulkInsertBenchmark.bulk50   thrpt   10  2,658 ± 0,209  ops/s
  * BulkInsertBenchmark.bulk100  thrpt   10  3,035 ± 0,498  ops/s
  * BulkInsertBenchmark.bulk200  thrpt   10  3,662 ± 0,276  ops/s
  */
@State(Scope.Thread)
class BulkInsertBenchmark extends IOValues with Resources with Randomness {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(10.seconds, 2.milliseconds)

  var dataList: Seq[Json]             = Seq.empty
  var client: ElasticSearchClient[IO] = _
  val index: String                   = genString()

  implicit private var system: ActorSystem = _
  private var server: ElasticServer        = _

  @Setup(Level.Trial) def doSetup(): Unit = {

    system = ActorSystem(s"BulkInsertBenchmark")
    implicit val ec: ExecutionContextExecutor     = system.dispatcher
    implicit val uc: UntypedHttpClient[IO]        = untyped[IO]
    implicit val timer: Timer[IO]                 = IO.timer(ec)
    implicit val retryConfig: RetryStrategyConfig = RetryStrategyConfig("never", 0.millis, 0.millis, 0, 0.millis)

    server = new ElasticServer() {}
    server.startElastic()

    client = ElasticSearchClient[IO](server.esUri)
    val indexPayload = jsonContentOf("/index_payload.json")
    client.createIndex(index, indexPayload)
    dataList = List.fill(200)(jsonContentOf("/resource.json"))
  }

  @TearDown(Level.Trial) def doTearDown(): Unit = {
    system.terminate()
    system.whenTerminated.futureValue
    server.stopElastic()
    server.system.terminate()
    val _ = server.system.whenTerminated.futureValue
  }

  @Benchmark
  def bulk1(): Unit =
    dataList.foreach { data => client.create(index, genString(), data).ioValue }

  @Benchmark
  def bulk10(): Unit = {
    val iter = dataList.iterator
    (0 until 20).foreach { _ =>
      val bulk = List.fill(10)(iter.next()).map(data => BulkOp.Index(index, genString(), data))
      client.bulk(bulk).ioValue
    }
  }

  @Benchmark
  def bulk20(): Unit = {
    val iter = dataList.iterator
    (0 until 10).foreach { _ =>
      val bulk = List.fill(20)(iter.next()).map(data => BulkOp.Index(index, genString(), data))
      client.bulk(bulk).ioValue
    }
  }

  @Benchmark
  def bulk50(): Unit = {
    val iter = dataList.iterator
    (0 until 4).foreach { _ =>
      val bulk = List.fill(50)(iter.next()).map(data => BulkOp.Index(index, genString(), data))
      client.bulk(bulk).ioValue
    }
  }

  @Benchmark
  def bulk100(): Unit = {
    val iter = dataList.iterator
    (0 until 2).foreach { _ =>
      val bulk = List.fill(100)(iter.next()).map(data => BulkOp.Index(index, genString(), data))
      client.bulk(bulk).ioValue
    }
  }

  @Benchmark
  def bulk200(): Unit = {
    val bulk = dataList.map(data => BulkOp.Index(index, genString(), data))
    client.bulk(bulk.toList).ioValue
  }
}
