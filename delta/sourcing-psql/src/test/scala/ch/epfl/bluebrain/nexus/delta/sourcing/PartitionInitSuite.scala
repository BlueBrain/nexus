package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.bio.BioSuite
import doobie.implicits.toSqlInterpolator

class PartitionInitSuite extends BioSuite {

  test("If the projectRef is not cached, we should obtain PartitionInit.Execute") {
    val projectRef = ProjectRef.unsafe("org", "project")
    for {
      cache <- KeyValueStore[String, Unit]()
      init  <- PartitionInit(projectRef, cache)
      _      = assertEquals(init, Execute(projectRef))
    } yield ()
  }

  test("If the projectRef is cached, we should obtain PartitionInit.Noop") {
    val projectRef = ProjectRef.unsafe("org", "project2")
    for {
      cache <- KeyValueStore[String, Unit]()
      init  <- PartitionInit(projectRef, cache)
      _      = assertEquals(init, Execute(projectRef))
      _     <- init.updateCache(cache)
      init2 <- PartitionInit(projectRef, cache)
      _      = assertEquals(init2, Noop)
    } yield ()
  }

  test("Noop should not do anything") {
    for {
      cache                  <- KeyValueStore[String, Unit]()
      partitionsBeforeUpdate <- cache.entries
      _                      <- Noop.updateCache(cache)
      partitionsAfterUpdate  <- cache.entries
      _                       = assertEquals(partitionsBeforeUpdate, partitionsAfterUpdate)
    } yield ()
  }

  test("Execute should update the cache") {
    val projectRef  = ProjectRef.unsafe("org", "project")
    val expectedKey = "9628a1046de38de7b6014110a178ea9e"
    for {
      cache          <- KeyValueStore[String, Unit]()
      _              <- cache.entries.assert(Map.empty)
      _              <- Execute(projectRef).updateCache(cache)
      updatedEntries <- cache.entries.map(_.keys.mkString(", "))
      _              <- cache
                          .containsKey(expectedKey)
                          .assert(true, s"We expected '$expectedKey' in cache, we only got '$updatedEntries'.")
    } yield ()
  }

  test("The org partition creation query should be correct") {
    val projectRef = ProjectRef.unsafe("bbp", "atlas")
    val query      = PartitionInit.createOrgPartition("scoped_events", projectRef)
    val expected   =
      sql"""
           | CREATE TABLE IF NOT EXISTS scoped_events_52466b6d740f6ded52c1ca5b37aceac7
           | PARTITION OF scoped_events FOR VALUES IN ('bbp')
           | PARTITION BY LIST (project);
           |""".stripMargin
    assertEquals(query.update.sql.strip, expected.update.sql.strip)
  }

  test("The project partition creation query should be correct") {
    val projectRef = ProjectRef.unsafe("bbp", "atlas")
    val query      = PartitionInit.createProjectPartition("scoped_events", projectRef)
    val expected   =
      sql"""
           | CREATE TABLE IF NOT EXISTS scoped_events_5741353a5fa12bd21cc6c19ecc97b256
           | PARTITION OF scoped_events_52466b6d740f6ded52c1ca5b37aceac7 FOR VALUES IN ('atlas')
           |""".stripMargin
    assertEquals(query.update.sql.strip, expected.update.sql.strip)
  }

}
