Mantis can be either configured using config files and JVM parameters.

## Config files

Several files are provided in order to change Mantis configuration. All of them can be found under relase `conf` folder.

Each file allows to configure a different aspect of the client. The entry point one is `mantis.conf. To override a setting, go to a specific file, uncomment a setting and provide a value.

### network.conf

- ethereum server port and address
- discovery and bootstrap nodes
- peer retries, limits 
- network identifier
- rpc server port and address. Enabled APIs

### storage.conf

- Keystore and nodeId locations
- Database directory
- Pruning configuration

### blockchain.conf

- Custom genesis file configuration (see private network)
- Chain specific parameters (chainId, homestead block number, dao fork number,etc)
- Monetary policy configuration

### sync.conf

- Fast sync configuration (disable, max concurrent requests, target block limit)

### misc.conf

- Transaction mempool size
- Mining configuration and coinbase
- RPC filter timeout

## JVM Parameters

In order to override a config value, keys included in configuration files can passed as Java system properties. For example to override `mantis.datadir`: 

- Linux: `./bin/mantis -Dmantis.datadir=/tmp/mantis_datadir`