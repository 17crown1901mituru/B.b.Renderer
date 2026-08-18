async function main() {
    const v = await Promise.resolve("ok");
    console.log(v);
}
main();