package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.migration

import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors

import java.nio.file.{Path, Paths}

object MigrationCheckHelper {

  private val DDLs: List[Path] = List(Paths.get("/scripts/drop-migration.ddl"), Paths.get("/scripts/migration.ddl"))

  def initTables(xas: Transactors)(implicit cl: ClassLoader) =
    DDLs.traverse(path => xas.execDDL(path.toString))

}
