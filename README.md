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

To release a new version:

* Run the following git command to add another git submodule:

```
 git submodule add -b $branch -f --name "versions/$branch" git@github.com:BlueBrain/nexus.git versions/$branch/
```

* Update the versions/versions.md file and create the new current / push the previous in the older releases
* Update the current branch in the build.sh file
* Example: https://github.com/BlueBrain/nexus/pull/5142
