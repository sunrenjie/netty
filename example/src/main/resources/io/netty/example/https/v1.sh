# create a private/public key pair and then a self-signed certificate using
# openssl commands
openssl genrsa -out v1.private-key.pem 2048  # generate a passwordless 2048-bit key in pkcs1 format
openssl rsa -in v1.private-key.pem -pubout -out v1.private-key.public-key.pem  # private key => public key
openssl pkcs8 -topk8 -in v1.private-key.pem -nocrypt -out v1.private-key.pkcs8.pem  # convert pkcs1 private key to unencrypted pkcs8 format
openssl req -new -key v1.private-key.pem -out v1.private-key.pem.csr  # create a CSR holding the key pair's identity
openssl x509 -req -days 365 -in v1.private-key.pem.csr -signkey v1.private-key  # create a self-signed certificate (signing the identity with its own private key)

