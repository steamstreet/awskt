package com.steamstreet.awskt.jwt

/**
 * Fixed vectors produced outside this codebase, by the OpenSSL 3.6 command line: the keys by
 * `openssl genpkey`, the signatures by `openssl dgst -sha256 -sign` (the ECDSA one converted from
 * DER to the raw r||s form JWS uses). They check this module against an independent
 * implementation on every target, including native, where the library under test is OpenSSL
 * linked into the binary rather than the JDK.
 *
 * The keys exist only for these tests and protect nothing.
 *
 * Both tokens: iss https://issuer.example, aud client-1, sub user-1, iat 1700000000,
 * exp 4102444800 (2100-01-01), and email_verified written as the string "true".
 */
internal object Vectors {
    const val RSA_JWK: String = """{"kty":"RSA","kid":"rsa-1","use":"sig","alg":"RS256","n":"4dlqAY8HzY7mgBOBZ_6Tjg-35hmhYqs1pzMBP_JQ7pYdol7lNw_tHu66FLc6xqBQV8DVGz_MZlAIl8SWXTW2_Z4k6NvifiuZN4ko_872Thx7M5RegjBQPJ9xGM9ce2NQZdV6IezCl4X6OlDWB_Gkbei9RZFdfstgAxDGqXC33xs1ExK9X9pvekdPP7Qg8Rajm0wj9gbtp7O0Uf0Tx_C3T0SZFDiMBNY5_-FtfFuqZqIw7Eso__bdUBfmb5qUuqLK0PeJuaBKqMpd6Kj2XPfw-YsukfwmoQx5xjLZpiFBvvtkcAUiZte5CquZbkhvhow4smBz4AQtWNMF0k5SG_Oquw","e":"AQAB"}"""
    const val EC_JWK: String = """{"kty":"EC","kid":"ec-1","use":"sig","alg":"ES256","crv":"P-256","x":"KpO3CWtUCWcJWyPS7TKQnpVbNO5hqdgpODNsd3GINT0","y":"Pn6-QujS2QxMMeKFqytXbFWiuD9N0EZZUJFNtZOiapw"}"""
    const val RS256_TOKEN: String = """eyJhbGciOiJSUzI1NiIsImtpZCI6InJzYS0xIiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwczovL2lzc3Vlci5leGFtcGxlIiwiYXVkIjoiY2xpZW50LTEiLCJzdWIiOiJ1c2VyLTEiLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6NDEwMjQ0NDgwMCwiZW1haWxfdmVyaWZpZWQiOiJ0cnVlIn0.exi8QBy-ASlMYJm7HYPu9QA1iOzb810_GfUJ11ZURj_V4sCQ-2TgWNEyEh2sUMIjO2cmhf79CgmMFG7IQ_xeMo2vqElIhBfzMJAKFaD1n-ibHejsrVJ85DJXvrbEOuixy8FV1vQbgiE1ZrEalDtQ0t2t1P5DFcum03-spSeCi4X-kxaFB7tCZp4bOL4omJjXte-f4IMeWklbZebqxc7Wl_Mp3KERYUcsCnhGng51zoNb22sVQHXnihmv1kpAeQ7EY--9JuvTLod8eBS1FDL-teB-Lanfqm7SD9Og5rMSE1QN2asLz7LXuDvwyqN7Xrpd8mtoxOAfw31rAUTmvTfyvg"""
    const val ES256_TOKEN: String = """eyJhbGciOiJFUzI1NiIsImtpZCI6ImVjLTEiLCJ0eXAiOiJKV1QifQ.eyJpc3MiOiJodHRwczovL2lzc3Vlci5leGFtcGxlIiwiYXVkIjoiY2xpZW50LTEiLCJzdWIiOiJ1c2VyLTEiLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6NDEwMjQ0NDgwMCwiZW1haWxfdmVyaWZpZWQiOiJ0cnVlIn0.aHX0qkIlPqtKP0q_-Cf1kzkm1V_WXQ25GdQhzc3GHMmSSkKUsscl-InVLAWLXFlvmup03NAXJYea23iN-1gbwg"""
    const val EC_PKCS8_PEM: String = """-----BEGIN PRIVATE KEY-----
MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgVuzCZpgbAksV+Tz+
LTwzWNBui/Cp8TVNV0PsjUZcILuhRANCAAQqk7cJa1QJZwlbI9LtMpCelVs07mGp
2Ck4M2x3cYg1PT5+vkLo0tkMTDHihasrV2xVorg/TdBGWVCRTbWTomqc
-----END PRIVATE KEY-----"""
    const val EC_SEC1_PEM: String = """-----BEGIN EC PRIVATE KEY-----
MHcCAQEEIFbswmaYGwJLFfk8/i08M1jQbovwqfE1TVdD7I1GXCC7oAoGCCqGSM49
AwEHoUQDQgAEKpO3CWtUCWcJWyPS7TKQnpVbNO5hqdgpODNsd3GINT0+fr5C6NLZ
DEwx4oWrK1dsVaK4P03QRllQkU21k6JqnA==
-----END EC PRIVATE KEY-----"""
    const val EC_PKCS8_BASE64: String = """MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgVuzCZpgbAksV+Tz+LTwzWNBui/Cp8TVNV0PsjUZcILuhRANCAAQqk7cJa1QJZwlbI9LtMpCelVs07mGp2Ck4M2x3cYg1PT5+vkLo0tkMTDHihasrV2xVorg/TdBGWVCRTbWTomqc"""
    const val RSA_PKCS8_PEM: String = """-----BEGIN PRIVATE KEY-----
MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDh2WoBjwfNjuaA
E4Fn/pOOD7fmGaFiqzWnMwE/8lDulh2iXuU3D+0e7roUtzrGoFBXwNUbP8xmUAiX
xJZdNbb9niTo2+J+K5k3iSj/zvZOHHszlF6CMFA8n3EYz1x7Y1Bl1Xoh7MKXhfo6
UNYH8aRt6L1FkV1+y2ADEMapcLffGzUTEr1f2m96R08/tCDxFqObTCP2Bu2ns7RR
/RPH8LdPRJkUOIwE1jn/4W18W6pmojDsSyj/9t1QF+ZvmpS6osrQ94m5oEqoyl3o
qPZc9/D5iy6R/CahDHnGMtmmIUG++2RwBSJm17kKq5luSG+GjDiyYHPgBC1Y0wXS
TlIb86q7AgMBAAECggEAD1WwSsJ361Sga6InagoRNxbzy8YCEmLWm0TYI7aOboy3
Hrm7wtqBeqqx7CB+zKgWRxxlkS/qmdMlqjcOZe4flSaCPLoFWGoZwU6+VRQr3s+J
6/gkgfQe1pGVinOLRNN694HLBPeq720zjsxt+TnGijHrJrNBOmU7s9Q/8OxMMnPp
RTW9dMj57NBU6NJ4UlWnTzXYLhfE9LqmEC/vKnqh9h2rRV0pmAOaWp+FRWUyFYXr
aiMwCrdEqM1Nu9XTMEiY+3pB3i67oY8eJb1t8knr918eDoWG/ELZEhE5MKN1M/mx
9gylQHFTF08jvWGbMtt0rgyTZ8uyxBjgnkOJb6tHrQKBgQD9jK+DshGeWlUPBTTG
XpHhdvZmkAJOlmRABIyq0BpaucQJcLdqBZgJ3PGnSgfXdvkZp9tqfPBCxm7uKPnF
xv1EFGO+xTp9dmDEowf6h8ZFmpBFb5FkYmm5NWPm3/ew+7TgX42e+Gtf/FyHBl/9
JdJHVPSy3rqs0WSOoYxU63yGjwKBgQDkCDHEvNKpYisCxIxY7L8iOat3WXXPLqKb
Z1tLmCW6YeFmjPTWkJJ6OPCHNySJCRubwZZxL+fQH4TURezBrNlHVX+DPycEnErM
byRfQG2rGtcfFImuTa4Ol1SsE8zuhZIOhoqibOxNg21b15Bakm7kelWWlXgvLvX+
lGuE5qHPFQKBgQD63lRa7blZAO/gKLqK+89DUj2CRULDFzKh1N6Js9YfpmY9IPWZ
RWeleqLvbuRLYEAgDmGe/3eJ2mSv6IMaGUVGMxZuDx9MO/CLHvQqAmU/QSs2SKmG
tYj948GxEjE1QBc7Wc/6VrmHA19Zigk3pFBmm7xxrsbtb1EbfuQmsclIKQKBgQDX
ft75m7BBnqIi9XfkuadPQszF92ccKmhFEIH4iIpu/v0yGtduxiWHF7RHNFd0oYTT
xzjTMoCR8Jdou8Qoq56SiTv93nqTItiVJhtrYMnDP5Q4rQIIFST+aQj9raCncNc8
nuz43pLaFfANMUQcM/JUPUARFKQFgw26TqzlZcdYmQKBgQDg2qniQGz10ehYFmJP
VjAw6qvOly0odPDCdDAncsKfhUN5BXDNwVBLQw0dsO/re14XKMGgl9+tbglU4jbe
+Z69DO7PZIH8ZjC5ckMLwttODhv35dqzYgoUrtv2cWJmSJmGxEhqOlXg4CLJ+RKv
cyXpQqK/4qNsIkx2zWMvYDnscQ==
-----END PRIVATE KEY-----"""
}
