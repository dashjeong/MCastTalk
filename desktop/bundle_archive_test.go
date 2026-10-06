package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Deterministic, embedded bz2 TAR fixtures. Generated with Python's stdlib;
// tests and production extraction use only Go's standard library. No commands,
// external compressor, actual inference or Windows runtime execution occur.
const bundleArchiveFixtures = `{"good":"QlpoOTFBWSZTWS7NguUAAOPfgeqAQAH/gAAhCEBvb95gBAAICDAAuKGlGjQ0AAGmgNMjQwAyaaDIYIaYjRgKooap4U9T1NqBk2moB6g9Tfb8wcGKkk85IibYhEM6Z1bEmIyZpMC6EQkJNHR7/fpkT4rhFxIhpprrQhl3WymKmtXN0rsmzwNS3sV3ldJqGjZEmZgLsWglA6QdaxQEALESfQSDGMGqBNjYgMc56wDdhJW/rH0UZOFHXi3OqrivRe8yD/F3JFOFCQLs2C5Q","traversal":"QlpoOTFBWSZTWedtx3UAAFxbgMqgQAHvgAEAb2XeYAgIIAB1DVNGg00aNqeoBk0bUElENANAAAB9fMNQgbAhCJ+HxkEsjkCGAwwxoePcJzBA6aAVQJ7tDtsUIOl5CRd719RNgjMSWwfNUmM1o1Hyx3pIPxdyRThQkOdtx3U=","absolute":"QlpoOTFBWSZTWU9se4kAAFxbgMqgQADvgABAb2XeYAgIIAB1EU2p6mj1NNGTQDGoyCSkNDQ0BoAAfYOIkIIo0IQ/iuYfQ6ZAhgMIYvraJrBGA2AL3QbAhWtQRyScUd5vd3KBhKgIRh8xVW0KrKvO+CSD8XckU4UJBPbHuJA=","backslash":"QlpoOTFBWSZTWct/lsIAAHLbgMqgQAB9AAIEb2XeYAgIIAB1DVNDTRo2hAaZPUbUElE0YgAAAH18BqEDZEIRHip5JGEyBDAYYY1OE5gi5tgY+Cxc/SLASpuPyZx76bhKnDRKlQa8wXxKSFb0ckIvzSQfi7kinChIZb/LYQA=","colon":"QlpoOTFBWSZTWQd5UtMAAG3fgMqgQABtkAgAABBvZd9gCAggAHURI0PUHpBiBp5QbUElI0NBoAAAH2biBCC9qEIfvS4fPLIgQwGGGNTRNYIvgYD2vo0LEhrjBoddoctTlAQ9S2YJZf8CzmauVpXDFiQfi7kinChIA7ypaYA=","alias":"QlpoOTFBWSZTWU5CbGgAAGXfjcqQQAH9AshC0ABvZd5gACAQAAgACAggAJIJKkemk0yAZGm1G0hpp6mnpBJKEyZM1HlHqDRtQABpj47+raGGrYAJVJIRDJUQsQ97inKIQyGYLppWcZ9GnX8iPI2jmEVjAjCU4ig2wpJmxcReM3U30/inXjyZ8i+Ky0hXIsfYX4O4OnWP4IJhnDdSRV3QiMi1mBtiUzaSrQp87rRIP4u5IpwoSCchNjQA","alias_multi":"QlpoOTFBWSZTWXAzQPcAAHzfgMqgQAHtAAgBwAB/Zd5gCAggAHQaU00aAAyeoD1NDIMkhpppo0AaAAPrmhRBAwjIIIdw9giaxZAIQBLExg+DlSgrQEDUQqaF24yKdO4zlBoPHiVMhqmbAkCeF90fCMIdRsjhSGNIiA/F3JFOFCQcDNA9wA==","trailing_dot":"QlpoOTFBWSZTWekRilIAAHnbgMqgQAHtgAEAb2XeYAgIIAB1DUnqDTQ0ZHqA9TQ2oJKQaGgBkAAfTUESEGMiEIp4snI3TtQIYDB8IOtaJrBBkwE7PSOJG1tANB5IJAapGwJNkhV3b8FnNFwWDhM9JB+LuSKcKEh0iMUpAA==","empty_segment":"QlpoOTFBWSZTWZz55LIAAG9bgMqgQAD9AAAEf2XeYAgIIAB1EKPUPUNNAeoD1NDygkpBo0A0NAAHz3ESEGEiEIq4tnLKXTIEMBhjCD3tE1ghJxCBaTZkgFSKL5K5b0XEwVglQfwOzcPB8LDQRED8XckU4UJCc+eSyA==","control":"QlpoOTFBWSZTWfTfX54AAHnbgOqgQADvgAEAb2XeYAgIIAB1DUnqDQNGmjQHqaHlBJRABoDQAAfNmGoQPhQhDuGSDp5Y0CHtBphjPXEKJgiJMBQfmWGkWtmETHkgcBqeVQNGSFHdfwVE6W9X2CR6SD8XckU4UJD031+e","symlink":"QlpoOTFBWSZTWdMgs8EAAG97gMiAABBAAHUAACQmLZ4AAAggAFRWmgaADQ0CKSGmgNAB9XeQoUtREx7zoYE5oJAQQMQJWSaWEI1KzwQzTndur0vEtPUAAUr4u5IpwoSGmQWeCA==","hardlink":"QlpoOTFBWSZTWbKGiFYAAIf7gcqAAQBAAH0AAAD/Zd5gBAAICCAAdBKUPUaANGgGgZBJKADRoAAA+fY08LNcyAII0kIc7ceUBC5SkohDAMr82qCKSAg4lEGTFWTmLLlOXzClIjquezqD9tHtYPgdqw+SMCHMbI9Nrp1EQPxdyRThQkLKGiFY","device":"QlpoOTFBWSZTWYIyIssAAHLbhMiAQABtBAAQbiAfAAAQAAggAFQ0owAAD1BIpNqDCABPbL4SCIBCI5wozMnAhDE3VpSYleV2goun0HTaBvR1s8JJkiIB4XckU4UJCCMiLLA=","fifo":"QlpoOTFBWSZTWZsNzcAAAGz7gMiAABBAAEWAAARhIJ4AAAggAFQ01GgANNBFFDTahoB91UihfNETbve5RayCQEEDECcSk1M5KqCPaZgzu9URAPi7kinChITYbm4A","duplicate":"QlpoOTFBWSZTWZj+7pcAAJ/bgcqAQADvgACAb2XeYAQACAggAIgSSgNGgAAAAqpEaepoaGagPUyNlPrBxAw1zAA0SSEPNykcFI8mJRCGEzLEnZwc1xC5YQSWoLXI6MWDJJFFYSTNGZN5PCbFuJtUMzaype1KqKPirBVivIu6ED+LuSKcKEhMf3dLgA==","case_directory":"QlpoOTFBWSZTWY7uPaQAAIjbgcqAQADvgECAf2XeYAQACAggAJAwyMCaYEyGJowCqKmmnqaj0J6MoxP1TTMTXDncslOMgm2IhC/h/JXHFeuUQhKJlLJ9vhyWkWpIZNiGxexwdLOLFaosopYY1P94dZ21dj8Vc28qzR4NrTezNWjRVqvavpcUZIg9i7kinChIR3ce0gA=","file_directory":"QlpoOTFBWSZTWVmvpk0AAJ3bgcqAQADvgACgf2XeYAQACAggAJUEqJiNNAD1AAACqSgaeppoGhoMQzUp06zaZJaJAlqiIQr34JNpYrTVIQkiUhZeYEKRwKcLBQiMFDChUKxLm1CDXjxYbHxiXXvRcvWqi9kjEt1s5sijNmwUWKOaZU7Ig/i7kinChILNfTJo","manifest_override":"QlpoOTFBWSZTWfpLkWEAAG7dgMqgQAN/AAAib3XeYAgIIAB1FTaE0aMgPKAeiNASUptGptJjQjEyMBpH888IQXtQhENbPJXRO5AhgMMMX7wmFMwQQUIFSXYDAltRmIRfqmR85aSONJVo6YbXewDZS/HMnOHqSLlI2NcbHuvSQdi7kinChIfSXIsI","ratio":"QlpoOTFBWSZTWbvYlW0AIHP7gNiAABBAAFWAAAhiAJ4QAAggAHUJTSnqBo2oNPUCpUA0ANB9WlqQgFl0REA0rktypixhEACBFnZnQMp0cJgvESoRir+4+rFVZmYIB+LuSKcKEhd7Eq2g","filecount":"QlpoOTFBWSZTWdwwO6ICBgNbgMiAQAD/4AAeZAAeAAgoYED/YAAAAAAAAAAAAAQzgB0UAKUoBRQAbmAAAgCdFzp6gLYIB7yHvPS22222222wWAc8lz3ttNVC0CAbz0N701tIyqBgHHoeD3sMIjAN3kPeV5gAYBzyHA89MBgHHocHoFC3IcAAEyXcAAPBkelFP01SqP1TEGQwaqqn/+qSe9/qKqoQTIDEMqp/+2AqlUjBBkYK1PfqlSqe//RVVIAMgCgAGgAAEmiUqenpUkAfqIP1OgAAAfYAAAH6AAAA/hHiBCSSSfy/P4/n+vnz5+37/x/MiAEQAiAEQAiAEQAiAEQAiAHc7gEQAiAEQAiAEQAiAEQAiAEQA7ncAiAEQAiAEQAiAEQAiAEQAiAHc7gEQAiAEQAiAEQAiAEQAiAEQA7ncAiAEQAiAEQAiAEQAiAEQAiAHc7gEQAiAEQAiAEQAiAEQAiAEQA7ncAiAEQAiAEQAiAEQAiAEQAiAHc7gEQAiAEQAiAEQAiAEQAiAEQA7ncAiAEQAiAEQAiAEQAiAEQAiAHc7gEQAiAEQAiAEQAiAEQAiAEQA+fPj58+ARACIARACIARACIARACIARADudwCIARACIARACIARACIARACIAdzuARACIARACIARACIARACIARADudwCIARACIARACIARACIARACIAdzuARACIARACIARACIARACIARADudwCIARACIARACIARACIARACIAdzuARACIARACIARACIARACIARADudwCIARACIARACIARACIARACIAdzuARACIARACIARACIARACIARADudwCIARACIARACIARACIARACIAfPnx8+fAIgBEAIgBEAIgBEAIgBEAIgB3O4BEAIgBEAIgBEAIgBEAIgBEAO53AIgBEAIgBEAIgBEAIgBEAIgB3O4BEAIgBEAIgBEAIgBEAIgBEAO53AIgBEAIgBEAIgBEAIgBEAIgB3O4BEAIgBEAIgBEAIgBEAIgBEAO53AIgBEAIgBEAIgBEAIgBEAIgB3O4BEAIgBEAIgBEAIgBEAIgBEAO53AIgBEAIgBEAIgBEAIgBEAIgB3O4BEAIgBEAIgBEAIgBEAIgBEAPnz4+fPgEQAiAEQAiAEQAiAEQAiAEQHvT3ve8IgBEAIgBEAIgBEAIgBEAO53AIgBEAIgBEAIgBEAIgBEAIgB3O4BEAIgBEAIgBEAIgBEAIgBEAO53AIgBEAIgBEAIgBEAIgBEAIgB3O4BEAIgBEAIgBEAIgBEAIgBEAO53AIgBEAIgBEAIgBEAIgBEAIgfPnz58+fPn+IAAAH5AAAA/sQAAAP9CAAAB8gAAAf1AgAARCAAAAwAAAH8Z+WqLPzbXrk1qiz553dttbVNtrapbVF/rzzzzzu7u22ttUpJIAXd1SALu6pXd/e973vec5znJJIAXd1S5znOckkgBd3VKSSAF3dUgD7JJJG222kkkSSQAO7ve+bbbaSSRNJCA7u975tJIkkgAd3e98SSf57W22ySSSNtttJJIkkgAd3e99JJJI2220kkiSSAB3d73zbbbSSSJJIAHd3vfJJJf022221ttttkkkkbbbaSSRJJAA7u9762222ySSSNtttJJIkkgAd3e99JJJI2220kkiSSAB3d73zbbb22221tttt1ttttkkkkbbbaSSRJJAA7u97bbbba2222ySSSNtttJJIkkgAd3e99bbbbZJJJG222kkkSSfz8/Pz58+d0ANtttgDW2223W2222SSSRtttpJJEkkADu7bbbbW2223W2222SSSRtttpJJEkkADu73tttttrbbbbJJJI2/zbSiSJJIAHfPndNtbbb+2223ve973sAa222262222ySSSNtttJJIkkgAbbbbSSSSa222262222ySSSNtttJJIkkgAd3bbbba222262222ySSSNtttJJIkkgAd3e9tttttttskklm223pJJJNbbbbdbbbbZJJJG222kkkSSdtts2229JJJJrbbbbrbbbbJJJI2220kkiSSABttttJJJJrbbbbrbbbbJJJI2220kkiSSAB3dttttrbaSSakklW223ZJJJLbbbbdbbbbZJJJG222kklttskklm223gDW2223W2220A973ve99+/fv36SSdtts2229JJJJrbbbbrbbbbJJJI2220kkiSSABttttJIABCSS+/fv379e973veAGttttuttttoEjbbb22xJJySSWbbbekkkk1tttt1ttttkkkkbbbaSSW22ySSWbfve9gDW2223W2220A973ve99+pJEknbbbNvu5gAMkktJJJtttvSSSSa222262222ySSSbbAAYkk5JJLNttvSSSSa222262222ySSSNttvbbEknJJJZttt6SSSTW2223W2222SSSRtttpJJbbbJe8u7kAAiSSkkks2229JJJJrbbbbrbbbbtu7sABiSTkkks2229JJJJrbbbbrbbbbJJJJtsABiSTkkks2228Aa222262222gHve973ttt+vj7x7uIABJJOSSSzbbb0kkkmttttu297d3YADEknJJJZttt6SSSTW2223W2223bd3YADEknJJJZttt4A1tttt1ttttANtvyfnd+fPnz8AAxJJySSWbbbekkkk1+vvXu6gAUkk1JJKtttuySSSW22227b3t3dgAMSSckklm223pJJJNbbbbdbbbbdvfe97d3YADEknJJJZttt6fT3p3dAAISSYkklG223JJJJNfr717uoAFJJNSSSrbbbskkklttttu38N727uwAGJJOSSSz+fvPu5gAMkktJJJtttvT6e9O7oABCSTEkko2225JJJJr9fevd1AApJJq+XvLu5AAIkkpJJKv5+8+7mAAySS0kkm2227Pp707ugAEPx9493EAAkkmL5e8u7kAAiSSkkko12q7d30RVF3YAdilVLu4ASSSd4uVXlKttvmMutu22845FepVtt7TIrrK++p7ap7trap73d221tU9888884kkADu73vs222kkkSSQAO7ve+0kkkbbbaSSRJJAA7u977W222ySSSNtttJJIkkgAd3e99tttttbbbbZJJJG222kkkSSQAO7ve22221tttt1ttttkkkkbbbaSSRJJAA7u2222kkkk1tttt1ttttkkkkbbbaSSRJJAA222zbbb0kkkmttttuttttskkkjbbbSSSJJO22ySSWbbbekkkk1tttt1ttttkkkkbbbaSSW22JJOSSSzbbb0kkkmttttuttttskkkjbbb22AAxJJySSWbbbekkkk1tttt1ttttkkkk23d2AAxJJySSWbbbekkkk1tttt1tttt297d3YADEknJJJZttt6SSSTW2223b7e9u7sABiSTkkks2229JJJJr9fevd1AApJJqSSVbbbdn096d3QACEkmJJJR/P3n3cwAGSSWvl7y7uQACPx9493EfD3h33e29u7ADEk5JJZttvSSSTW2227bbba2222SSSNttpJIkkAd3tvffffffffYAAAH64q+sjatZFfdsyK590lXJSK1lgrUKCtYWK1JRWsjatZFd3LzIr22ZFc9pKuSkVrLBWoUFawsVqSit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veNTaTTTkkiTbaTkkir3vZmYAAAB3799+u/wzM44444u/tkkkk7d0AAAAABvvd++/Xf4ZmcccccXf2ySSSdu6AAAAAA7Psu8HZnHHHHF39skkknbugAAAAAOz7LvB2Zxxxxxd/bJJJJ27oAAAA222229/PxfrIr82zIrn5pKuSkVrLBWoUFawsVqSitZG1ayK7bMiufnnnjIrz5pKuSkVrLBWoUFawsVqSitZG1ayK7bMiufNJVyfryTlyUitZYK1CgrWFitSUVrI2rWRXtsyK580lXJSK1nEy1jDLzLBWoUFawsVqSitZG1ayK7JrVNtu7ve+RJIAHd3vySKJJIAHRtltJJIkkiSQyRtttpJJH9bTbbJJJI2221+2x221ttttkkkke2G22ttttuttttrb5ttgDW2223Xdttve973vaSSSTW22pJJVttt2SSEkmJJJRtsABkklpLu5AAIn3j3cR8PeHfd7b27sAMSTkklm229JJJNbbbbttttrbbbZJJI222kkiSQB3ettttv5/fw+/d913wzM44444u/tkkkk7d0AAAAABvvV7v3367/DMzjjjji7+2SSSTt3QAAAAAG173vd++/Xf4ZmcccccXf2ySSSdu6AAAAAAHmknvK5d8AkmhoaGhoXdezMzMzN3dAAAAAAb71e97v3367/DMzjjjji7+2SSSTt3QAAAAAG+r1V7v3367/DMzjjjji7+2SSSTt3QAAAAAG1XvVXfvv13+GZnHHHHF39skkknbugAAAAAA822kt5XLvgEk0NDQ0NC7r2ZmZknbugAAAAAOz7LvB2Zxxxxxd/bJJJJ27oAAAAADs+y7wdmcccccXf2ySSSdu6AAAAAABspS7qlALu6pbJIAXd1vOcnJJIADvzbLaSSRJJH6SGSNtttJJI/rabbQD3ve97339tt+tttbbbbQD22/NttbbbbdbbbbW3zbbAGttttuu9ttm223pJJJNbbakklW223ZJISSYkklG2wAGSSWku7kAAifePdxHw94d93tvbuwAxJOSSWbbb0kkk1tttu2222ttttkkkjbbaSSJJAXf0kkkk7u+674dmcccccXf2ySSSdu6AAAAAA7Psu8HZnHHHHF39skkknbugAAAAAOz7LvB2Zxxxxxd/bJJJJ27oAAAAADs+y7wdmcccccXf2ySSSdu6AAAAAA7Psu8HZnHHHHF39skkknbugAAAAAOz7LvB2Zxxxxxd/bJJJJ27oAAAAADs+y7wdmcccccXf2ySSSdu6AAAAAA7Psu8HZnHHHHF39skkknbugAAAAAOz7LvB2Zxxxxxd/bJJJJ27oAAAAADs+y7wdmcccccXf2ySSSdu6AAAAAA7PvZd/fewXd/fe7MzAA7u9+SRRJJAA782y2kkkSSR+khkjbbbSSSP62m22SSSRtttr9tjttrbbbaAe235ttrbbbbrbbba2+bbYA1tttt13bbb3ve972ANbbakklW223ZJISSYkklG2wAGSSWku7kAAifePdxHw94d93tvbuwAxJOX379+73ve97ANbbbbttttrbbbQfPne973vOc5ySQC7rMzMzMzd2tu9A2TQ0444u/tkkkk7d0AAAAAB2fZd4OzOOOOOLv7ZJJJO3dAAAAAAdn2XeDszjjjji7+2SSSTt3QAAAAAHZ9l3g7M44444u/tkkkk7d0AAAAAB2fZd4OzOOOOOLv7ZJJJO3dAAAAAAdn2XeDszjjjji7+2SSSTt3QAAAAAHZ9l3g7M44444u/tkkkk7d0AAAAAB2fZd4ccccccXf0kkkk7d0AAAAAB2fZd4ccccccXf0kkkk7d0AAAAAB2fZd4ccccccXf0kkkk7d0AAAAAB/X1e973veAP/YAAAH8/gAAAH/0AAAD+QAAAP5AAAA/kAAAD5JAAAP8i7kinChIbhgd0Q=","truncated":"QlpoOTFBWSZTWS7NguUAAOPfgeqAQAH/gAAhCEBvb95gBAAICDAAuKGlGjQ0AAGmgNMjQwAyaaDIYIaYjRgKooap4U9T1NqBk2moB6g9Tfb8wcGKkk85IibYhEM6Z1bEmIyZpMC6EQkJNHR7/fpkT4rhFxIhpprrQhl3WymKmtXN0rsmzwNS3sV3ldJqGjZEmZgLsWglA6QdaxQEALESfQSDGMGqBNjYgMc56wDdhJW/rH0UZOFHXi3OqrivRe8y","checksum":"QlpoOTFBWSZTWS7NguUAAOPfgeqAQAH/gAAhCEBvb95gBAAICDAAuKGlGjQ0AAGmgNMjQwAyaaDIYIaYjRgKooap4U9T1NqBk2moB6g9Tfb8wcGKkk85IibYhEM6Z1bEmIyZpMC6EQkJNHR7/fpkT4rhFxIhpprrQhl3WymKmtXN0rsmzwNS3sV3ldJqGjZEmZgLsWglA6QdaxQEALESfQSDGMGqBNjYgMc56wDdhJW/rH0UZOFHXi3OqrivRe8yD/F3JFOFCQLs2C7Q"}`

func bundleTestBytes(t *testing.T, label string) []byte {
	t.Helper()
	var fixtures map[string]string
	if err := json.Unmarshal([]byte(bundleArchiveFixtures), &fixtures); err != nil {
		t.Fatal(err)
	}
	b, err := base64.StdEncoding.DecodeString(fixtures[label])
	if err != nil || len(b) == 0 {
		t.Fatal(label, err)
	}
	return b
}
func bundleTestManager(t *testing.T, label, task string, persist func(InstalledAsset) error) (*AssetManager, string, string) {
	t.Helper()
	root := portableTestTemp(t)
	b := bundleTestBytes(t, label)
	path := filepath.Join(root, "source.tar.bz2")
	if err := os.WriteFile(path, b, 0600); err != nil {
		t.Fatal(err)
	}
	am := NewAssetManager(filepath.Join(root, "data"), persist)
	id := "bundle-fixture-" + label
	am.registry = append(am.registry, Artifact{ID: id, Task: task, Format: "tar.bz2", Bytes: int64(len(b)), SHA256: portableTestHash(b)})
	return am, id, path
}
func TestArtifactBundleTarSafetyAndManifest(t *testing.T) {
	for _, task := range []string{"tts", "runtime"} {
		t.Run(task, func(t *testing.T) {
			var installed InstalledAsset
			am, id, path := bundleTestManager(t, "good", task, func(a InstalledAsset) error { installed = a; return nil })
			if err := am.Import(context.Background(), id, path); err != nil {
				t.Fatal(err)
			}
			folder := "runtimes"
			if task == "tts" {
				folder = "models"
			}
			if !engineManagedPath(am.dataDir, folder, installed.Path) {
				t.Fatal("wrong bundle install root", installed.Path)
			}
			if err := verifyArtifactBundle(context.Background(), installed.Path); err != nil {
				t.Fatal(err)
			}
			raw, err := os.ReadFile(filepath.Join(installed.Path, "installed-manifest.json"))
			if err != nil {
				t.Fatal(err)
			}
			var manifest bundleManifest
			if err = json.Unmarshal(raw, &manifest); err != nil || len(manifest.Files) != 2 {
				t.Fatal(manifest, err)
			}
			for _, f := range manifest.Files {
				if err := verifyModel(context.Background(), filepath.Join(installed.Path, filepath.FromSlash(f.Path)), f.SHA256, f.Bytes, nil); err != nil {
					t.Fatal(err)
				}
			}
			if err := os.WriteFile(filepath.Join(installed.Path, "extra"), []byte("unexpected"), 0600); err != nil {
				t.Fatal(err)
			}
			if err := verifyArtifactBundle(context.Background(), installed.Path); err == nil {
				t.Fatal("unlisted extra file accepted")
			}
		})
	}
}
func TestArtifactBundleTarRejectsUnsafeBeforeCommit(t *testing.T) {
	for _, label := range strings.Fields("traversal absolute backslash colon alias alias_multi trailing_dot empty_segment control symlink hardlink device fifo duplicate case_directory file_directory manifest_override ratio filecount truncated checksum") {
		t.Run(label, func(t *testing.T) {
			commits := 0
			am, id, path := bundleTestManager(t, label, "tts", func(InstalledAsset) error { commits++; return nil })
			if err := am.Import(context.Background(), id, path); err == nil {
				t.Fatal("unsafe TAR installed", label)
			}
			if commits != 0 {
				t.Fatal("unsafe tree committed")
			}
			entries, _ := os.ReadDir(filepath.Join(am.dataDir, "models"))
			if len(entries) != 0 {
				t.Fatal("failed import left activated directory", entries)
			}
			if _, err := os.Stat(filepath.Join(filepath.Dir(am.dataDir), "escaped")); !os.IsNotExist(err) {
				t.Fatal("traversal touched outside tree")
			}
		})
	}
}
func TestArtifactBundlePersistFailureRollbackAndCancel(t *testing.T) {
	am, id, path := bundleTestManager(t, "good", "tts", func(InstalledAsset) error { return errors.New("synthetic persist failure") })
	if err := am.Import(context.Background(), id, path); err == nil {
		t.Fatal("failed persistence reported success")
	}
	entries, _ := os.ReadDir(filepath.Join(am.dataDir, "models"))
	if len(entries) != 0 {
		t.Fatal("failed persistence retained a new tree")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := am.Import(ctx, id, path); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	var saved InstalledAsset
	am.persist = func(a InstalledAsset) error { saved = a; return nil }
	if err := am.Import(context.Background(), id, path); err != nil {
		t.Fatal(err)
	}
	am.persist = func(InstalledAsset) error { return errors.New("second persistence failure") }
	if err := am.Import(context.Background(), id, path); err == nil {
		t.Fatal("second persistence failure hidden")
	}
	if err := verifyArtifactBundle(context.Background(), saved.Path); err != nil {
		t.Fatal("failed repeated import removed previous working tree", err)
	}
	// A cached tree with a coherently rewritten self-manifest still must match
	// the freshly re-extracted pinned source on a repeated asset import.
	manifestPath := filepath.Join(saved.Path, "installed-manifest.json")
	b, _ := os.ReadFile(manifestPath)
	var manifest bundleManifest
	if err := json.Unmarshal(b, &manifest); err != nil {
		t.Fatal(err)
	}
	payload := []byte("replacement tree with a matching rewritten self-manifest")
	if err := os.WriteFile(filepath.Join(saved.Path, filepath.FromSlash(manifest.Files[0].Path)), payload, 0600); err != nil {
		t.Fatal(err)
	}
	manifest.Files[0].Bytes, manifest.Files[0].SHA256 = int64(len(payload)), portableTestHash(payload)
	b, _ = json.Marshal(manifest)
	if err := atomicFile(manifestPath, b); err != nil {
		t.Fatal(err)
	}
	if err := verifyArtifactBundle(context.Background(), saved.Path); err != nil {
		t.Fatal("fixture must demonstrate coherent self-manifest rewrite", err)
	}
	if err := am.Import(context.Background(), id, path); err == nil || !strings.Contains(err.Error(), "verified source archive") {
		t.Fatal("cache rewrite was not rejected against re-imported original archive", err)
	}
}

type bundleCancelReader struct{ cancel context.CancelFunc }

func (r bundleCancelReader) Read(p []byte) (int, error) { r.cancel(); p[0] = 'x'; return 1, nil }
func TestArtifactBundleCancellationAndLimits(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	_, err := copyBundleFile(ctx, portableTestTemp(t), "file", 2, bundleCancelReader{cancel})
	if !errors.Is(err, context.Canceled) {
		t.Fatal("stream cancellation ignored", err)
	}
	g := bundleGuard{}
	if err := g.add("huge", false, bundleMaxFile+1); err == nil {
		t.Fatal("single-file limit ignored")
	}
	g = bundleGuard{total: bundleMaxTotal}
	if err := g.add("more", false, 1); err == nil {
		t.Fatal("total limit ignored")
	}
	if bundleRatio(501, 1) == nil || bundleRatio(500, 1) != nil {
		t.Fatal("ratio bound incorrect")
	}
	if _, err := copyBundleFile(context.Background(), portableTestTemp(t), "short", 2, io.LimitReader(strings.NewReader("x"), 1)); err == nil {
		t.Fatal("truncated entry accepted")
	}
}
func TestArtifactBundleOfficialArchives(t *testing.T) {
	if os.Getenv("MCAST_OFFICIAL_TTS_BUNDLES") != "1" {
		t.Skip("opt-in verified official archive extraction; no inference")
	}
	archiveDir := officialArchiveTestDir(t)
	for _, id := range []string{"runtime-sherpa-tts", "tts-supertonic3", "tts-kokoro-zh"} {
		t.Run(id, func(t *testing.T) {
			path := filepath.Join(archiveDir, id+".tar.bz2")
			var installed InstalledAsset
			am := NewAssetManager(filepath.Join(portableTestTemp(t), "data"), func(a InstalledAsset) error { installed = a; return nil })
			if err := am.Import(context.Background(), id, path); err != nil {
				t.Fatal(err)
			}
			if err := verifyArtifactBundle(context.Background(), installed.Path); err != nil {
				t.Fatal(err)
			}
		})
	}
}
