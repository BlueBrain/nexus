# Workflow (alpha)

Nexus Fusion’s goal is to bring together the information you have available in a structured and standardized format.

Fusion Workflow allows users to organize their work in clearly defined data-driven steps, and update their team on the progress.

Workflow makes it possible to visualize activities, data, code, notes, and configured inputs for each step.

Fusion Workflow (alpha) is available starting from Nexus version 1.5.

## Project

To create a new project, use `Create New Project Button` and fill in the form:

@@@ div { .center }
![Step Card](../assets/fusion-workflow-project-create.png)
@@@

Then you can start adding Workflow Steps and data tables to your project.

## Workflow Steps

A Workflow Step acts as a bucket (grouping) of provenance activities.

Steps are represented by draggable cards and can be created manually in the Project View and Workflow Step View (for sub-steps).

@@@ div { .center }
![Step Card](../assets/fusion-workflow-step-cards.png)
@@@

Each Workflow Step card contains its title, description, a list of sub-steps and step's status: not started, in progress, blocked, and done.

Steps can be visually linked with previous steps. It can be done easily in the form that pops up when you click '+' button on a card.

@@@ div { .center }
![Step Form](../assets/fusion-workflow-create-step.png)
@@@

To modify a Workflow Step, click `Step Info` button in the Workflow Step View and update fields in the form:

@@@ div { .center }
![Step Form](../assets/fusion-workflow-step-update.png)
@@@

Notice, that the description supports markdown text.
