# Blue Brain Nexus Documentation

This branch contains the content for the [Blue Brain Nexus website](https://bluebrainnexus.io/). 

It contains the product page as well as a list of git submodules for Nexus versions we want to build and deploy the documentation.

To build locally host the website, run the following:

```bash
# if checking out website for the first time
$ git submodule init
# if updating to the latest changes
$ git submodule update --remote --merge
$ ./build.sh
```

The product page and the documentation should be built and extracted under the target directory.