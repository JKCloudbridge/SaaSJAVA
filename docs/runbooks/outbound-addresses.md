# Runbook: outbound addresses

Sprint 33 (decisions in [ADR-0291](../adr/0291-environments-the-matrix-outbound-addresses-and-what-staging-may-do.md)).

## When to use this

A customer allows calls only from known addresses (a webhook receiver, an ERP system's firewall, a file server) and asks where the
platform's calls come from; or the number of gateways changes.

## The fact

Every outbound call of an environment (webhooks, connectors, file transfer) leaves from the application's private network through a
NAT gateway with a **fixed address**. The addresses are an output of the infrastructure files (`nat_addresses`), per environment:

```
tofu output nat_addresses        (run in deploy/infra/stack with the environment's backend and values)
```

Give the customer the addresses of **production** only; staging's are its own and staging makes no outbound call by default.
Production has one gateway by default (one address). With `nat_gateway_count = 2` there is one per zone: give the customer both.
Changing the count or recreating a gateway may change an address only if the address resource is removed; the addresses belong to
the environment and are kept when a gateway is replaced.

## What the platform refuses to call

Plain http, a name that resolves to a private, loopback, link-local, carrier-grade, documentation or multicast address (so the
deployment's own network and every address inside it), the cloud's metadata addresses (the instance metadata address, the container
credentials endpoint, the IPv6 metadata address), an address with user information, a query or an unusual port form. A host listed
under `platform.integration.egress.development-hosts` is allowed over http and may be private, which is why the `prod` profile
**refuses to start** when the list is not empty. The hostile-address corpus (`hostile-addresses.txt`) holds every one of these.

## How I know it worked

`tofu output nat_addresses` lists the addresses; a test call from a receiver you control shows one of them as the source; the
customer's firewall rule names them.
