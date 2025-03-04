package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import doobie.syntax.all._

object PartitionQueries {

  def partitionsOf(mainTable: String)(implicit xas: Transactors): IO[List[String]] = {
    val partitionPrefix = s"$mainTable%"
    sql"""|SELECT table_name from information_schema.tables
          |WHERE table_name != $mainTable
          |AND table_name LIKE $partitionPrefix
          |ORDER BY table_name""".stripMargin
      .query[String]
      .to[List]
      .transact(xas.read)
  }

}
