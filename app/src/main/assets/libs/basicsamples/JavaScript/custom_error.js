class MyError extends Error {}
try {
    throw new MyError("boom")
} catch (e) {
    console.log(e.message)
}