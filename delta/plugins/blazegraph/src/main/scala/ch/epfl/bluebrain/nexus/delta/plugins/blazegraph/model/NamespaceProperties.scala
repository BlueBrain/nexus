package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

final case class NamespaceProperties(value: Map[String, String]) {
  def +(key: String, value: String): NamespaceProperties = copy(value = this.value + (key -> value))

  override def toString: String = value.map { case (key, value) => s"$key=$value" }.mkString("\n")
}

object NamespaceProperties {

  val defaultValue: NamespaceProperties = NamespaceProperties(
    Map(
      "com.bigdata.rdf.store.AbstractTripleStore.textIndex"                       -> "true",
      "com.bigdata.rdf.store.AbstractTripleStore.axiomsClass"                     -> "com.bigdata.rdf.axioms.NoAxioms",
      "com.bigdata.rdf.sail.isolatableIndices"                                    -> "false",
      "com.bigdata.rdf.sail.truthMaintenance"                                     -> "false",
      "com.bigdata.rdf.store.AbstractTripleStore.justify"                         -> "false",
      "com.bigdata.namespace.test-ns.spo.com.bigdata.btree.BTree.branchingFactor" -> "1024",
      "com.bigdata.rdf.store.AbstractTripleStore.quads"                           -> "true",
      "com.bigdata.namespace.test-ns.lex.com.bigdata.btree.BTree.branchingFactor" -> "400",
      "com.bigdata.rdf.store.AbstractTripleStore.geoSpatial"                      -> "false",
      "com.bigdata.rdf.store.AbstractTripleStore.statementIdentifiers"            -> "false"
    )
  )

}
