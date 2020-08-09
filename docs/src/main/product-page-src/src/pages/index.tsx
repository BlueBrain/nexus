import * as React from "react"
import MainLayout from "../layouts/Main"
import WhatIsNexus from "../components/WhatIsNexus"
import MainHero from "../components/MainHero"
import WeAreOpenSource from "../components/WeAreOpenSource"
import Features from "../components/Features"
import PoweredByNexus from "../components/PoweredByNexus"
import GetStartedWithNexus from "../components/GetStarted"
import AnyQuestions from "../components/AnyQuestions"
import EmailCatch from "../containers/EmailCatch"

const features = [
  {
    title: "Science-Driven",
    description:
      "While most companies have a plethora of people and tools to manage their data, scientists and researchers don’t have the time nor resources to do so. We developed Nexus by focusing on their specific needs, and by integrating with their existing workflows.",
  },
  {
    title: "Production-Ready",
    description:
      "Developed to cover the need of any data-intensive team, laboratory or organization. You can manage your data and knowledge graph in a secure and scalable fashion. The level of performance can be scaled to your needs, and we take extra-precautions to protect your data.",
  },
  {
    title: "All-in-One",
    description:
      "A complete suite of integrated tools and services to organize, manage and leverage your data. You could start structuring your data by using our Forge python framework, store it using our Delta web services, and easily access and share it through our Fusion web interface. We also have a CLI to manage the instance, and javascript and python libraries to develop your own apps.",
  },
  {
    title: "Versatile",
    description:
      "Ready to cope with any field of application, Nexus allows you to structure and evolve your data continuously. Extensive use of data representation standards. Easily used in combination with your Data Science tools.",
  },
  {
    title: "Value from Data",
    description:
      "From organizing your data to drawing value from them, Nexus has the tools you need. Keep track of data lineage to enable evaluation of data quality and experiment reproducibility. Ensure valuable data is made available and reused by your team, your organization or the rest of the world. Leverage your Knowledge Graph to fuel your next Artificial Intelligence application.",
  },
  {
    title: "Extensible",
    description:
      "Nexus is built with flexibility and extensibility in mind. Beyond the tools and services offered, build your own extensions to suit your needs. For example, you can create your own data mappers in Forge, data visualisation plugins in Fusion and react to Knowledge Graph events in Delta. ",
  },
]

export default function Home() {
  return (
    <MainLayout>
      <MainHero />
      <WhatIsNexus />
      <WeAreOpenSource />
      <Features
        title="Why Nexus?"
        subtitle="Nexus was born out of a need for better data management in the
            simulation neuroscience field, but built with genericity in mind.
            The data management needs of scientists is something that we value
            and aim at making as easy as possible."
        features={features}
      />
      <PoweredByNexus />
      <GetStartedWithNexus />
      <EmailCatch />
      <AnyQuestions />
    </MainLayout>
  )
}
