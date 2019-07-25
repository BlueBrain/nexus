    
# Set up


## Overview

This tutorial explains how to install and set up the Blue Brain Nexus CLI to connect to a Blue Brain Nexus deployment.

@@@ note
* This tutorial makes use of an AWS deployment of Blue Brain Nexus available at https://sandbox.bluebrainnexus.io.
* We will be using [Nexus CLI](https://github.com/BlueBrain/nexus-cli), a python client,  to interact with the deployment.
@@@

Let's get started.

## Install the Blue Brain Nexus CLI

Since the CLI is written in python, you may want to create a virtual environment for a clean set up. To do so, [Conda](https://conda.io/en/latest/) can be used. If you don't have it installed follow the instructions [here](https://conda.io/projects/conda/en/latest/user-guide/install/index.html).

```shell
conda create -n nexus-cli python=3.6
```

```shell
conda activate nexus-cli
```

```shell
pip install git+https://github.com/BlueBrain/nexus-cli
```

The following command should output a help message if the installation is successful.

Command
:   @@snip [nexus-help.sh](../assets/nexus-help.sh)

Output
:   @@snip [nexus-help-out.sh](../assets/nexus-help-out.sh)


## Connect to a Nexus deployment

### Configure the CLI 

To ease the usage of the CLI, we will create a profile named 'tutorial' storing locally various configurations such as the Nexus deployment url.

Command
:   @@snip [create-profile-cmd.sh](../assets/create-profile-cmd.sh)

Output
:   @@snip [create-profile-out.sh](../assets/create-profile-out.sh)


### Login

A bearer token is needed to authenticate to Nexus. For the purpose of this tutorial, you'll login using your github account.

The following command will open (after pressing enter button) a browser window from where you can login using your github account.


Command
:   @@snip [login-auth-cmd.sh](../assets/login-auth-cmd.sh)

Output
:   @@snip [login-auth-out.sh](../assets/login-auth-out.sh)

From the opened web page, click on the login button on the right corner and follow the instructions.


![login-ui](../assets/login-ui.png)

At the end you'll see a token button on the right corner. Click on it to copy the token.

![login-ui](../assets/copy-token.png)

The token can now be added to the tutorial profile. In the output of the following command you should see that the token column has now an expiry date.

Command
:   @@snip [settoken-auth-cmd.sh](../assets/settoken-auth-cmd.sh)

Output
:   @@snip [settoken-auth-out.sh](../assets/settoken-auth-out.sh)


That's it!