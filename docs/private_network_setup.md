In order to setup a private network, the following steps are needed:

1. Create a custom genesis configuration file (geth format): 
```
{
  "difficulty": "0x0400000000",
  "extraData": "0x11bbe8db4e347b4e8c937c1c8370e4b5ed33adb3db69cbdb7a38e1e50b1b82fa",
  "gasLimit": "0x1388",
  "nonce": "0x0000000000000042",
  "ommersHash": "0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347",
  "timestamp": "0x0",
  "coinbase": "0x0000000000000000000000000000000000000000",
  "mixHash": "0x0000000000000000000000000000000000000000000000000000000000000000",
  "alloc": {
    "000d836201318ec6899a67540690382780743280": {"balance": "200000000000000000000"},
    "001762430ea9c3a26e5749afdb70da5f78ddbb8c": {"balance": "200000000000000000000"}
    .....
  }
```

2. Configure genesis file in `blockchain.conf` `custom-genesis-file` property
3. Edit `chain-id` and other configs in `blockchain.conf` if needed