// Fusion icons
import studiosIcon from "../../static/img/icons/dashboard.svg"
import pluginsIcon from "../../static/img/icons/jigsaw.svg"
import adminIcon from "../../static/img/icons/admin.svg"
import searchIcon from "../../static/img/icons/monitor.svg"
import graphIcon from "../../static/img/icons/graph.svg"

// Forge icons
import shareIcon from "../../static/img/icons/share.svg"
import foldersIcon from "../../static/img/icons/folders.svg"
import lockIcon from "../../static/img/icons/padlock.svg"
import cloudIcon from "../../static/img/icons/cloud.svg"
import researchIcon from "../../static/img/icons/research.svg"

type Icon = {
  name: string
  iconSrc: any
}

const featureIcons: Icon[] = [
  {
    name: "Studios",
    iconSrc: studiosIcon,
  },
  {
    name: "Extensible",
    iconSrc: pluginsIcon,
  },
  {
    name: "Administration",
    iconSrc: adminIcon,
  },
  {
    name: "Search",
    iconSrc: searchIcon,
  },
  {
    name: "Graph Exploration",
    iconSrc: graphIcon,
  },
  {
    name: "Data Management",
    iconSrc: foldersIcon,
  },
  {
    name: "Scalable & Secure",
    iconSrc: lockIcon,
  },
  {
    name: "Flexible Storage",
    iconSrc: cloudIcon,
  },
  {
    name: "Powerful indexing",
    iconSrc: researchIcon,
  },
  {
    name: "Extensibility",
    iconSrc: pluginsIcon,
  },
  {
    name: "Federation",
    iconSrc: shareIcon,
  },
]

const getIcon = (title: string) => {
  return featureIcons.find(icon => icon.name === title)?.iconSrc || ""
}

export default getIcon
