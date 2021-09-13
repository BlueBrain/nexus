package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{typed, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.typed.{Cluster => TCluster, Join}
import akka.testkit.TestKit
import cats.effect.concurrent.Deferred
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.AbstractDBSpec.config
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import com.typesafe.config.{Config, ConfigFactory}
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, CancelAfterFailure}
import slick.jdbc.H2Profile.api._
import slick.jdbc.JdbcBackend
import slick.jdbc.JdbcBackend.Database

import java.io.File
import java.util.UUID
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.reflect.io.Directory

// $COVERAGE-OFF$
abstract class AbstractDBSpec
    extends TestKit(ActorSystem("AbstractDBSpec", config))
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with CancelAfterFailure {

  implicit private val scheduler: Scheduler            = Scheduler.global
  implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped

  @SuppressWarnings(Array("NullAssignment"))
  private val db: JdbcBackend.Database = AbstractDBSpec.beforeAll

  override protected def afterAll(): Unit = {
    val cacheDirectory = new Directory(new File(config.getString("akka.cluster.distributed-data.durable.lmdb.dir")))
    if (cacheDirectory.exists) cacheDirectory.deleteRecursively()
    shutdown()
    AbstractDBSpec.afterAll(db)
    super.afterAll()
  }

}

object AbstractDBSpec {

  def config: Config = ConfigFactory
    .parseString(
      s"""test-instance = "${UUID.randomUUID()}""""
    )
    .withFallback(
      ConfigFactory.parseResources("akka-persistence-test.conf")
    )
    .withFallback(
      ConfigFactory.parseResources("akka-test.conf")
    )
    .withFallback(
      ConfigFactory.load()
    )
    .resolve()

  def beforeAll(implicit
      system: ActorSystem,
      typedSystem: typed.ActorSystem[Nothing],
      sc: Scheduler
  ): JdbcBackend.Database = {
    val database = Task
      .fromFuture {
        val db = Database.forConfig("h2.db", system.settings.config)
        db.run {
          sqlu"""
            DROP TABLE IF EXISTS "event_tag";
            DROP TABLE IF EXISTS "event_journal";
            DROP TABLE IF EXISTS "snapshot";
            CREATE TABLE IF NOT EXISTS "event_journal" (
                "ordering" BIGINT NOT NULL AUTO_INCREMENT,
                "deleted" BOOLEAN DEFAULT false NOT NULL,
                "persistence_id" VARCHAR(255) NOT NULL,
                "sequence_number" BIGINT NOT NULL,
                "writer" VARCHAR NOT NULL,
                "write_timestamp" BIGINT NOT NULL,
                "adapter_manifest" VARCHAR NOT NULL,
                "event_payload" BLOB NOT NULL,
                "event_ser_id" INTEGER NOT NULL,
                "event_ser_manifest" VARCHAR NOT NULL,
                "meta_payload" BLOB,
                "meta_ser_id" INTEGER,
                "meta_ser_manifest" VARCHAR,
                PRIMARY KEY("persistence_id","sequence_number")
                );

            CREATE UNIQUE INDEX IF NOT EXISTS "event_journal_ordering_idx" on "event_journal" ("ordering");

            CREATE TABLE IF NOT EXISTS "event_tag" (
                "event_id" BIGINT NOT NULL,
                "tag" VARCHAR NOT NULL,
                PRIMARY KEY("event_id", "tag"),
                CONSTRAINT fk_event_journal
                  FOREIGN KEY("event_id")
                  REFERENCES "event_journal"("ordering")
                  ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS "snapshot" (
                "persistence_id" VARCHAR(255) NOT NULL,
                "sequence_number" BIGINT NOT NULL,
                "created" BIGINT NOT NULL,"snapshot_ser_id" INTEGER NOT NULL,
                "snapshot_ser_manifest" VARCHAR NOT NULL,
                "snapshot_payload" BLOB NOT NULL,
                "meta_ser_id" INTEGER,
                "meta_ser_manifest" VARCHAR,
                "meta_payload" BLOB,
                PRIMARY KEY("persistence_id","sequence_number")
                );"""
        }.as(db)
      }
      .runSyncUnsafe(30.seconds)

    val lock = Deferred[Task, Unit].runSyncUnsafe()
    Cluster(system).registerOnMemberUp {
      lock.complete(()).runSyncUnsafe()
    }

    val cluster = TCluster(typedSystem)
    cluster.manager ! Join(cluster.selfMember.address)

    lock.get
      .timeout(5.seconds)
      .onErrorRecoverWith { case _: TimeoutException =>
        Task.raiseError(new TimeoutException("Failed to subscribe to the cluster after 5 seconds."))
      }
      .runSyncUnsafe()

    database
  }

  def afterAll(db: JdbcBackend.Database): Unit =
    db.close()

}
