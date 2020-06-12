import sbt._
import sbt.librarymanagement.ModuleFilter

import scala.xml.transform.{RewriteRule, RuleTransformer}
import scala.xml.{Elem, Node, NodeSeq}

object XmlTransformer {

  /**
    * Constructs a new XML [[scala.xml.transform.RuleTransformer]] based on the argument ''blacklist'' module filter
    * that strips out ''dependency'' xml nodes that are matched by the ''blacklist''.
    *
    * @param blacklist the filter to apply to dependencies
    * @return a new XML [[scala.xml.transform.RuleTransformer]] that strips out ''blacklist''ed dependencies
    */
  def transformer(blacklist: ModuleFilter): RuleTransformer =
    new RuleTransformer(new RewriteRule {
      override def transform(node: Node): NodeSeq =
        node match {
          case e: Elem if e.label == "dependency" =>
            val organization = e.child.filter(_.label == "groupId").flatMap(_.text).mkString
            val artifact     = e.child.filter(_.label == "artifactId").flatMap(_.text).mkString
            val version      = e.child.filter(_.label == "version").flatMap(_.text).mkString
            if (blacklist(organization % artifact % version)) NodeSeq.Empty else node
          case _                                  => node
        }
    })
}
