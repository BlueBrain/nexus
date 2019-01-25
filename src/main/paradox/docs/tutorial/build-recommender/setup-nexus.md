

# Set up Nexus environment

## Install Nexus Python SDK

The Nexus Python SDK is available at https://github.com/BlueBrain/nexus-python-sdk.

You can install the SDK using

```bash
pip install nexus-sdk
```

**Development version**

```bash
pip install git+https://github.com/BlueBrain/nexus-python-sdk
```

**Development mode**

```bash
git clone https://github.com/bluebrain/nexus-python-sdk
pip install --editable nexus-python-sdk
```

## Set up the SDK environment

Before using the SDK to ingest and access data in Nexus, you should set up your SDK environment.

First, please get your access token through Github.

Then, set the access token and your Nexus deployment endpoint in your SDK configuration.
```
nexus.config.set_environment('YOUR DEPLOYMENT URL')
nexus.config.set_token('YOUR ACCESS TOKEN')
```