# Creating a Studio Step by Step

A studio is a place where you can organize your data and configure custom plugins to visualize them.

In this example, we'll make one that simply displays images from files.

## Set up your project

We've uploaded some nice neuron morphology images from [Neuromorpho.org](neuromorpho.org), which are showing up in our project.

Now, nexus-web has out-of-the-box image preview support for images less than 3MB inside the normal project view, but in this example we have customers who want to view the data as organized inside tables.

![Studio set up with neuron images](../assets/studio-guide-neurons-project.png);

## Create your studio

You can create a studio from within a project by clicking on the Project Action Menu on the right hand side, under the Studios tab.

![Create a Studio](../assets/studio-guide-create-studio-button.png);

It will prompt you to come up with a label for your project. Here we called it Neuron Studio. Think of a Studio as a landing page for all your data to be organized. In most cases you'll only need one Studio.

![Create a Studio](../assets/studio-guide-create-neuron-studio.png);

Next, create a workspace. A workspace allows you to group your queries and visualizations together. We'll create a workspace to put all our artifacts, that is files as opposed to json-ld datasets. In this case, our neuron images.

![Create a Workspace](../assets/studio-guide-create-neuron-artifacts-workspace.png);
