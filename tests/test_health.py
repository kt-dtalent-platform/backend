def test_health(client):
    res = client.get("/v1/health")
    assert res.status_code == 200
    assert res.json() == {"data": {"status": "ok"}}


def test_unknown_path_uses_error_format(client):
    res = client.get("/v1/nope")
    assert res.status_code == 404
    assert res.json()["error"]["code"] == "NOT_FOUND"
