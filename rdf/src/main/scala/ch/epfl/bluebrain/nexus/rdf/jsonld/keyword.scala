package ch.epfl.bluebrain.nexus.rdf.jsonld

object keyword {
  val context     = "@context"
  val id          = "@id"
  val tpe         = "@type"
  val prefix      = "@prefix"
  val vocab       = "@vocab"
  val none        = "@none"
  val base        = "@base"
  val container   = "@container"
  val language    = "@language"
  val list        = "@list"
  val nest        = "@nest"
  val set         = "@set"
  val index       = "@index"
  val graph       = "@graph"
  val reverse     = "@reverse"
  val value       = "@value"
  val version     = "@version"
  val `import`    = "@import"
  val `protected` = "@protected"
  val propagate   = "@propagate"
  val json        = "@json"
  val included    = "@included"
  val direction   = "@direction"

  val all =
    Set(
      context,
      id,
      tpe,
      prefix,
      vocab,
      base,
      container,
      language,
      list,
      set,
      nest,
      index,
      graph,
      reverse,
      value,
      version,
      `import`,
      `protected`,
      propagate,
      json,
      included,
      direction,
      none
    )
}
