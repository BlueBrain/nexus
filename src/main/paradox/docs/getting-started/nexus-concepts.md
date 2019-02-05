# Brief introduction of Nexus concepts

The following paragraph intends to give a high-level introduction to basic concepts implemented in 
Blue Brain Nexus from a user perspective. The introduction is intended for a reader who wants to get a quick 
overview and feel for the concepts within Blue Brain Nexus. This description is not intended to be technical. 

The following concepts will be addressed in this paragraph: **Organizations, Projects, Schemas, Data, Resolvers, 
Views** and **Resources**.

To better explain these basic concepts of Blue Brain Nexus, let’s imagine you have some data, for example 
the data from your latest clinical research study. Since Blue Brain Nexus is your data management platform of 
choice, you want to store your data - e.g. the files and metadata for each study participant - in a structured 
manner that makes sense to you, your collaborators and your research field, update the data (e.g. to correct a typo) 
and search for them in a meaningful way (e.g. to retrieve only the data which was collected from female study 
participants). The concept of **Organizations** and **Projects** in Nexus both help you structure the data you want to 
store - a bit like the folder structure of an operating system which allows you to group data.  Organizations 
are the highest level under which you can group your data. You could e.g. create an organization for the 
department you are working in to help you collect all the data from your department under this organization. 
Located within organizations are projects. Much like organizations, projects allow you to group your data. 
You could e.g. create a project for the data from your research group within your department organization in 
Blue Brain Nexus in which you will then store the data from your latest clinical research study. While both 
organizations and projects help you to group your data and control who can access them, projects allow you to 
define additional settings applicable to everything within it, e.g. you can define how the identifier for e.g. a 
schema within a given project should be composed. So what are **Schemas** in the context of Blue Brain Nexus? Schemas 
are a collection of rules or constraints applied to e.g. **Data** (or other Resources in Blue Brain Nexus). Different 
types of data can be described with different schemas, e.g. if you want to store data on a person in Blue Brain 
Nexus, you could use a schema which requires you to provide a first name and a surname in your data, while if you 
want to store data on a research institute, you could use a schema which requires you to provide the name and the 
address of the institute. In brief, schemas in Nexus help ensure that (meta-)data is stored in the desired shape 
and that required (meta-)data is provided. So far, this paragraph described that you can “bucket” your data in 
organizations and projects and that you can validate them against a selected schema. However, one can imagine 
situations in which it is desirable to bridge projects while avoiding isolation, e.g. if you want to re-use a 
schema inside your project which one of your collaborators had already developed for a separate project in Blue 
Brain Nexus. For this purpose, Blue Brain Nexus provides a “bridging” mechanism called **Resolvers**. By defining a 
resolver, you can e.g. bring a schema from one project in Blue Brain Nexus within the scope of another. Once your 
data is stored in Blue Brain Nexus in the desired structure, you most certainly want to perform all sorts of 
searches on them. For search purposes, Blue Brain Nexus offers so-called **Views**, of which there can be several, 
each relying on a different technology (e.g. ElasticSearch). Finally, all concepts in this paragraph, including 
organizations, projects, schemas, data, resolvers and views, are generically referred to as **Resources** within Blue 
Brain Nexus.